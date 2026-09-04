#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
忘记密码：发信通道连通性实测。

在你自己的机器上跑（要能连公司网 / 外网），后端容器和 Claude 那边都连不到任何 SMTP。

用法：
    # 公司邮箱发给自己（最关键的一条：能不能投递到 @pwrd.com）
    python3 scripts/test_auth_mail.py --channel pwrd --to yangjunjun@pwrd.com

    # 私人邮箱发到公司邮箱（外部进公司，最容易被反垃圾网关拦）
    python3 scripts/test_auth_mail.py --channel sina --to yangjunjun@pwrd.com

    # 公司邮箱发到私人邮箱
    python3 scripts/test_auth_mail.py --channel pwrd --to trav_yang@sina.cn

密码不写进文件，也不进命令行历史：
    - 交互式输入（默认），或
    - 环境变量 MAIL_PASSWORD

新浪邮箱注意：用的是「客户端授权码」，不是网页登录密码。
需要先到 新浪邮箱 → 设置 → 客户端pop/imap/smtp 里开启 SMTP 并生成授权码。
"""

import argparse
import getpass
import os
import random
import smtplib
import ssl
import sys
import traceback
from datetime import datetime
from email.header import Header
from email.mime.text import MIMEText
from email.utils import formataddr, formatdate

CHANNELS = {
    "pwrd": {
        "label": "公司邮箱（Exchange）",
        "host": "mail.mx.wanmei.net",
        "port": 587,
        "user": "yangjunjun@pwrd.com",
        "mode": "starttls",
        # 公司 Exchange 常见自签 / 主机名不匹配证书，与 ReleaseMailConfig 的处理保持一致
        "relaxed_tls": True,
    },
    "sina": {
        "label": "私人邮箱（新浪）",
        "host": "smtp.sina.cn",
        "port": 465,
        "user": "trav_yang@sina.cn",
        "mode": "ssl",
        "relaxed_tls": False,
    },
}


def build_message(sender, to, code, channel_label):
    now = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    body = f"""你好，

有人正在为你的测试平台账号申请重置密码。验证码是：

    {code}

有效期 10 分钟，仅可使用一次。请回到测试平台客户端填写。

申请时间：{now}
发信通道：{channel_label}（连通性测试）

如果不是你本人操作，请忽略这封邮件，并联系平台管理员。
本邮件由系统自动发送，请勿直接回复。
"""
    msg = MIMEText(body, "plain", "utf-8")
    msg["Subject"] = Header("【测试平台】密码重置验证码", "utf-8")
    msg["From"] = formataddr((str(Header("测试平台", "utf-8")), sender))
    msg["To"] = to
    msg["Date"] = formatdate(localtime=True)
    return msg


def send(cfg, sender, to, password, debug):
    ctx = ssl.create_default_context()
    if cfg["relaxed_tls"]:
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE

    code = f"{random.randint(0, 999999):06d}"
    msg = build_message(sender, to, code, cfg["label"])

    if cfg["mode"] == "ssl":
        server = smtplib.SMTP_SSL(cfg["host"], cfg["port"], timeout=25, context=ctx)
    else:
        server = smtplib.SMTP(cfg["host"], cfg["port"], timeout=25)
    try:
        if debug:
            server.set_debuglevel(1)
        server.ehlo()
        if cfg["mode"] == "starttls":
            server.starttls(context=ctx)
            server.ehlo()
        server.login(cfg["user"], password)
        refused = server.sendmail(sender, [to], msg.as_string())
        return code, refused
    finally:
        try:
            server.quit()
        except Exception:
            pass


def main():
    ap = argparse.ArgumentParser(description="忘记密码发信通道实测")
    ap.add_argument("--channel", choices=sorted(CHANNELS), required=True)
    ap.add_argument("--to", required=True, help="收件人")
    ap.add_argument("--from-addr", help="发件地址，默认与登录账号相同（多数服务器要求一致）")
    ap.add_argument("--debug", action="store_true", help="打印完整 SMTP 会话")
    args = ap.parse_args()

    cfg = CHANNELS[args.channel]
    sender = args.from_addr or cfg["user"]
    password = os.environ.get("MAIL_PASSWORD") or getpass.getpass(
        f"{cfg['user']} 的密码/授权码（不回显）: "
    )

    print(f"\n通道   : {cfg['label']}")
    print(f"服务器 : {cfg['host']}:{cfg['port']} ({cfg['mode']})")
    print(f"发件人 : {sender}")
    print(f"收件人 : {args.to}\n")

    try:
        code, refused = send(cfg, sender, args.to, password, args.debug)
    except smtplib.SMTPAuthenticationError as e:
        print(f"❌ 认证失败：{e.smtp_code} {e.smtp_error!r}")
        if args.channel == "sina":
            print("   新浪要用「客户端授权码」而不是网页登录密码，且需先在网页端开启 SMTP。")
        else:
            print("   确认账号密码是否已过期（公司账号通常 90 天强制改一次）。")
        return 2
    except (smtplib.SMTPConnectError, OSError) as e:
        print(f"❌ 连不上服务器：{e}")
        print("   公司 SMTP 只在公司网内可达——确认是否连着内网或 VPN。")
        return 3
    except smtplib.SMTPException as e:
        print(f"❌ SMTP 出错：{e}")
        traceback.print_exc()
        return 4

    if refused:
        print(f"⚠️  服务器接了信但拒了部分收件人：{refused}")
        return 5

    print(f"✅ 已投交服务器，验证码 {code}")
    print("\n还没完，去收件箱确认这三件事：")
    print("  1. 收到了吗？多久到的？")
    print("  2. 在收件箱还是垃圾箱？（进垃圾箱等于这条通道不可用）")
    print("  3. 发件人显示成什么？收件人会不会当成钓鱼邮件？")
    return 0


if __name__ == "__main__":
    sys.exit(main())
