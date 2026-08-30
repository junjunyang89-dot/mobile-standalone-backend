# -*- coding: utf-8 -*-
"""mitmproxy 插件：把经过的请求写成一行一条的 JSON。

清单里只有 method / path / status / 耗时 / traceId 这些能进库的。
其余（请求头、Cookies、请求/响应体）<b>另存</b>到旁边的 details 目录，一条一个文件：
「看响应」是抓包的头号用途，当场看不了就等于没解决问题；
但它也<b>不该进库</b>——一次抓包的 body 动辄几十 MB，而「这个需求走了哪些接口」
只需要路径。所以：会话期间随时能看，会话结束随临时文件一起消失。

域名过滤是这里最重要的一步：不过滤的话，一次操作抓出来四十个请求，
埋点、心跳、图片 CDN、第三方 SDK 占绝大多数。看两次之后这份清单就没人看了。

用法：
    mitmdump -s flow_writer.py --set out=/path/flows.jsonl --set hosts=a.com,b.com
"""
import json
import os
import time

# 单条 body 的上限。超过就截断——真要看几 MB 的响应，那是下载文件不是读接口，
# 而把它整个读进浏览器只会让页面卡死
MAX_BODY = 256 * 1024

# 只存文本类。图片/视频/protobuf 存下来也是一堆乱码，白占地方
TEXT_HINTS = ("json", "text", "xml", "javascript", "x-www-form-urlencoded", "html")

from mitmproxy import ctx


def _size(msg):
    """整个报文的大小，含起始行和头——不是只有 body。

    只算 body 的话，GET 请求会显示成 0 B，而手机上的抓包工具显示 538 B。
    对不上的数字比没有数字更糟：人会以为是抓漏了。
    """
    try:
        head = len(bytes(msg.headers))
        if hasattr(msg, "method"):
            line = len("%s %s %s\r\n" % (msg.method, msg.path, msg.http_version))
        else:
            line = len("%s %s %s\r\n" % (msg.http_version, msg.status_code, msg.reason or ""))
        return head + line + len(msg.raw_content or b"")
    except Exception:  # noqa: BLE001
        return len(getattr(msg, "raw_content", b"") or b"")


class FlowWriter:
    def __init__(self):
        self.out_path = None
        self.hosts = []
        self.started = time.time()
        # 被白名单挡掉的域名也要记一笔。
        # 不记的话，「一条都没抓到」有三种可能——代理没通、证书没装、域名不在白名单，
        # 而这三种的排查方向完全不同。记下来就能直接告诉人是哪一种
        self.seen = {}
        # 握手/连接失败的域名。这是最容易被误判的一类：
        # App 固定了证书时，连接根本建不起来，既没有 response 也不会进 seen——
        # 人看到的是「代理通着但什么都抓不到」，会以为是代理坏了，
        # 而实际上是这个域名<b>抓不了</b>，换个白名单也没用
        self.failed = {}
        # 给每条流水一个序号，页面靠它回来取 body
        self.seq = 0

    def load(self, loader):
        loader.add_option("out", str, "", "输出的 jsonl 路径")
        loader.add_option("hosts", str, "", "只抓这些域名，逗号分隔；留空＝全抓")

    def configure(self, updates):
        if "out" in updates:
            self.out_path = ctx.options.out or None
        if "hosts" in updates:
            raw = ctx.options.hosts or ""
            self.hosts = [h.strip().lower() for h in raw.split(",") if h.strip()]

    def _wanted(self, host):
        if not self.hosts:
            return True
        h = (host or "").lower()
        # 后缀匹配：配 arca42.com 时 gateway.arca42.com 也要命中，
        # 但不能让 evilarca42.com 混进来，所以要求前一个字符是点
        for want in self.hosts:
            if h == want or h.endswith("." + want):
                return True
        return False

    def _note_failed(self, host, why):
        if not host:
            return
        key = "%s（%s）" % (host, why)
        self.failed[key] = self.failed.get(key, 0) + 1
        try:
            with open(self.out_path + ".failed", "w", encoding="utf-8") as f:
                json.dump(self.failed, f, ensure_ascii=False)
        except Exception:  # noqa: BLE001
            pass

    def _note_seen(self, host):
        if not host:
            return
        self.seen[host] = self.seen.get(host, 0) + 1
        try:
            with open(self.out_path + ".hosts", "w", encoding="utf-8") as f:
                json.dump(self.seen, f, ensure_ascii=False)
        except Exception:  # noqa: BLE001
            pass

    def _is_text(self, headers):
        ct = (headers.get("content-type") or "").lower()
        return any(h in ct for h in TEXT_HINTS)

    def _cookies(self, holder):
        try:
            return {k: v[0] for k, v in holder.cookies.items(multi=True)}
        except Exception:  # noqa: BLE001
            return {}

    def _dump_detail(self, seq, flow):
        """请求头、Cookies、请求/响应体，一条一个文件。

        <b>永远都写</b>，哪怕响应是二进制。头和 body 是两回事：
        图片的响应体没什么好看的，但它的请求头照样是排查问题的关键
        （token 对不对、appversion 是多少、走没走对 host）。
        早先的版本在这里直接 return，结果二进制那条连请求头都点不开。
        """
        info = {"respTruncated": False, "respKind": "", "bodyNote": ""}
        try:
            d = self.out_path + ".details"
            os.makedirs(d, exist_ok=True)
            info["respKind"] = (flow.response.headers.get("content-type") or "").split(";")[0]

            body = ""
            if self._is_text(flow.response.headers):
                raw = flow.response.raw_content or b""
                if len(raw) > MAX_BODY:
                    info["respTruncated"] = True
                    raw = raw[:MAX_BODY]
                body = raw.decode("utf-8", errors="replace")
            else:
                info["bodyNote"] = "%s 是二进制内容，不留响应体" % (info["respKind"] or "该类型")

            payload = {
                "reqHeaders": dict(flow.request.headers),
                "reqCookies": self._cookies(flow.request),
                "reqBody": self._req_body(flow),
                "respHeaders": dict(flow.response.headers),
                "respCookies": self._cookies(flow.response),
                "respBody": body,
                "bodyNote": info["bodyNote"],
            }
            with open(os.path.join(d, "%d.json" % seq), "w", encoding="utf-8") as f:
                json.dump(payload, f, ensure_ascii=False)
        except Exception:  # noqa: BLE001
            # 写不下来不影响这条流水本身——清单还在，只是点不开详情
            pass
        return info

    def _req_body(self, flow):
        if not self._is_text(flow.request.headers):
            return ""
        raw = flow.request.raw_content or b""
        return raw[:MAX_BODY].decode("utf-8", errors="replace")

    def request(self, flow):
        """请求一到就记一笔。

        只在 response 里记的话，「发出去了但没回来」的请求（超时、被重置、
        服务端 5xx 之前断开）在诊断里完全不存在，而那恰恰是最需要看到的一类。
        """
        if not self.out_path:
            return
        try:
            self._note_seen(flow.request.pretty_host)
        except Exception:  # noqa: BLE001
            pass

    def error(self, flow):
        """这条流出错了——连接被重置、上游拒绝、超时。"""
        if not self.out_path:
            return
        try:
            host = flow.request.pretty_host if flow.request else None
            msg = str(getattr(flow, "error", "")) or "连接出错"
            self._note_failed(host, msg[:60])
        except Exception:  # noqa: BLE001
            pass

    def tls_failed_client(self, data):
        """客户端 TLS 握手失败。

        <b>要记下真实的错误码，不能只写「握手失败」</b>——不同的错误指向完全不同的原因：
          unknown ca / certificate unknown  → 手机不信任 mitmproxy 的证书
          certificate required              → 服务端要客户端证书
          handshake failure / protocol      → TLS 版本或加密套件对不上
          bad certificate（证书本身没问题时）→ App 固定了证书
        笼统写一句「多半是 pinning」会把人带到错的方向：同一台手机上别的域名
        抓得到、就这个不行时，原因几乎一定不是「手机不信任证书」。
        """
        try:
            ctx_ = data.context
            sni = getattr(ctx_.client, "sni", None)
            addr = getattr(ctx_.server, "address", None)
            host = sni or (addr[0] if addr else None)
            why = ""
            for src in (getattr(data, "conn", None), ctx_.client):
                err = getattr(src, "error", None)
                if err:
                    why = str(err)
                    break
            self._note_failed(host, ("TLS 握手失败：" + why) if why else "TLS 握手失败")
        except Exception:  # noqa: BLE001
            pass

    def response(self, flow):
        if not self.out_path:
            return
        # 不在这儿记 seen：request 钩子已经记过一次，
        # 两处都记的话每个请求会被数两遍，「代理上共 N 个请求」直接翻倍
        if not self._wanted(flow.request.pretty_host):
            return
        try:
            started = flow.request.timestamp_start or 0
            ended = flow.response.timestamp_end or 0
            # traceId 是抓包和服务端日志之间唯一的共同 key。
            # 没有它，出问题时只能拿时间戳去日志里捞，那是碰运气
            trace = None
            for key in ("pwrd-request-id", "x-request-id", "traceid", "x-trace-id"):
                trace = flow.response.headers.get(key) or flow.request.headers.get(key)
                if trace:
                    break
            self.seq += 1
            info = self._dump_detail(self.seq, flow)

            # 对端地址。连接复用时可能拿不到，拿不到就留空
            remote = ""
            try:
                peer = flow.server_conn.peername
                if peer:
                    remote = "%s:%s" % (peer[0], peer[1])
            except Exception:  # noqa: BLE001
                pass

            path_only = flow.request.path.split("?")[0]
            row = {
                "seq": self.seq,
                "method": flow.request.method,
                "host": flow.request.pretty_host,
                "path": path_only,
                "query": flow.request.path[len(path_only) + 1:],
                # 完整 URL：带 query 才能直接贴到 Postman 里重放
                "url": flow.request.pretty_url,
                "scheme": flow.request.scheme,
                "httpVersion": flow.request.http_version,
                "remote": remote,
                "status": flow.response.status_code,
                "reason": flow.response.reason or "",
                "durationMs": int((ended - started) * 1000) if ended and started else None,
                "at": int(started * 1000) if started else None,
                "traceId": trace,
                "reqContentType": flow.request.headers.get("content-type") or "",
                "respContentType": flow.response.headers.get("content-type") or "",
                # 整个报文大小，和手机抓包工具的 Request/Response Package 对得上
                "reqBytes": _size(flow.request),
                "respBytes": _size(flow.response),
                "respKind": info["respKind"],
                "respTruncated": info["respTruncated"],
                "bodyNote": info["bodyNote"],
                # 详情永远都有——二进制只是没有响应体，头和 Cookies 照样能看
                "hasDetail": True,
            }
            with open(self.out_path, "a", encoding="utf-8") as f:
                f.write(json.dumps(row, ensure_ascii=False) + "\n")
        except Exception as e:  # noqa: BLE001
            # 单条写失败不该中断整场抓包——抓包过程里报错，人是看不到的
            ctx.log.warn("flow_writer 写入失败: %s" % e)


addons = [FlowWriter()]
