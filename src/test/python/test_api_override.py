# -*- coding: utf-8 -*-
"""接口响应改写的单测。

这组测试守的第一条不是「改对了没有」，是**改不到的时候必须吵**。
静默放过的后果是：整批用例以为自己在测另一个分支，实际没有，而且全绿——
这比不改写糟得多，它生产的是一个错误的结论，且没有任何迹象。
"""
import json
import os
import shutil
import sys
import tempfile
import time
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__),
                                "..", "..", "main", "resources", "capture"))
import api_override as ov  # noqa: E402


APP_CONFIG = json.dumps({
    "code": 0,
    "data": {
        "appConfigs": {
            "myTabSysWebSwitch": "1",
            "deadlockSwitch": 1,
            "vipAndroidSwitch": "1",
        },
        "list": [{"name": "a"}, {"name": "b"}],
    },
}, ensure_ascii=False)


def rule(rid, **kw):
    r = {"id": rid, "match": kw.pop("match", {"path": "/getAppConfigs"})}
    r.update(kw)
    return r


class MatchTest(unittest.TestCase):

    def test_路径用包含匹配(self):
        r = rule("a", match={"path": "/getAppConfigs"})
        # 真实路径常带版本前缀，要求全等的话每个人都得先抓一次包才会写规则
        self.assertTrue(ov.matches(r, "GET", "api.x.com", "/api/v2/getAppConfigs"))
        self.assertFalse(ov.matches(r, "GET", "api.x.com", "/api/v2/getUserInfo"))

    def test_域名按后缀匹配但不能被冒充(self):
        r = rule("a", match={"host": "wmpvp.com", "path": "/x"})
        self.assertTrue(ov.matches(r, "GET", "gw.wmpvp.com", "/x"))
        self.assertFalse(ov.matches(r, "GET", "evilwmpvp.com", "/x"),
                         "evilwmpvp.com 被当成了 wmpvp.com 的子域")

    def test_method留空等于不限制(self):
        r = rule("a", match={"path": "/x"})
        self.assertTrue(ov.matches(r, "POST", "h", "/x"))
        r2 = rule("a", match={"path": "/x", "method": "POST"})
        self.assertFalse(ov.matches(r2, "GET", "h", "/x"))


class ApplyTest(unittest.TestCase):

    def test_把开关掰到另一个状态(self):
        r = rule("sysweb-off", set={"data.appConfigs.myTabSysWebSwitch": "0"})
        res = ov.apply_overrides([r], "GET", "h", "/getAppConfigs", 200, APP_CONFIG)
        self.assertEqual(["sysweb-off"], res.applied)
        self.assertEqual([], res.problems)
        self.assertEqual("0", json.loads(res.body)["data"]["appConfigs"]["myTabSysWebSwitch"])
        # 其它字段不能被动到
        self.assertEqual(1, json.loads(res.body)["data"]["appConfigs"]["deadlockSwitch"])

    def test_数组下标(self):
        r = rule("x", set={"data.list[1].name": "changed"})
        res = ov.apply_overrides([r], "GET", "h", "/getAppConfigs", 200, APP_CONFIG)
        self.assertEqual([], res.problems)
        self.assertEqual("changed", json.loads(res.body)["data"]["list"][1]["name"])

    def test_不命中的规则不动响应(self):
        r = rule("x", match={"path": "/other"}, set={"data.appConfigs.myTabSysWebSwitch": "0"})
        res = ov.apply_overrides([r], "GET", "h", "/getAppConfigs", 200, APP_CONFIG)
        self.assertFalse(res.changed)
        self.assertEqual(APP_CONFIG, res.body)

    # ---------- 故障注入 ----------

    def test_改状态码(self):
        r = rule("boom", status=500)
        res = ov.apply_overrides([r], "GET", "h", "/getAppConfigs", 200, APP_CONFIG)
        self.assertEqual(500, res.status)
        self.assertEqual(["boom"], res.applied)

    def test_整体替换响应体(self):
        r = rule("empty", body={"code": 0, "data": {"list": []}})
        res = ov.apply_overrides([r], "GET", "h", "/getAppConfigs", 200, APP_CONFIG)
        self.assertEqual([], json.loads(res.body)["data"]["list"])

    def test_延迟取最大值(self):
        rs = [rule("a", delayMs=1000), rule("b", delayMs=5000)]
        res = ov.apply_overrides(rs, "GET", "h", "/getAppConfigs", 200, APP_CONFIG)
        self.assertEqual(5000, res.delay_ms)


class MustNotSilentlyPassTest(unittest.TestCase):
    """这一组是整个模块存在的理由。"""

    def test_字段不存在时整条不生效并报出来(self):
        r = rule("typo", set={"data.appConfigs.myTabSysWebSwitchX": "0"})
        res = ov.apply_overrides([r], "GET", "h", "/getAppConfigs", 200, APP_CONFIG)
        self.assertEqual([], res.applied, "改不到却被记成已生效")
        self.assertEqual(1, len(res.problems))
        self.assertIn("myTabSysWebSwitchX", res.problems[0])
        self.assertEqual(APP_CONFIG, res.body, "改不到还动了响应体")

    def test_一处改不到则整条都不落地(self):
        # 一个对一个错：绝不能只改对的那个
        r = rule("half", set={
            "data.appConfigs.myTabSysWebSwitch": "0",
            "data.appConfigs.nope": "0",
        })
        res = ov.apply_overrides([r], "GET", "h", "/getAppConfigs", 200, APP_CONFIG)
        self.assertEqual([], res.applied)
        self.assertEqual("1", json.loads(res.body)["data"]["appConfigs"]["myTabSysWebSwitch"],
                         "部分生效了——响应被改成了一个既不是 A 也不是 B 的形状")

    def test_默认不创建新字段(self):
        # App 只读它已知的字段，凭空造一个它根本不会看，
        # 但规则会显示「已生效」——那是一次被当成生效的空操作
        r = rule("new", set={"data.appConfigs.brandNew": "1"})
        res = ov.apply_overrides([r], "GET", "h", "/getAppConfigs", 200, APP_CONFIG)
        self.assertEqual([], res.applied)
        self.assertIn("brandNew", res.problems[0])

    def test_显式声明create才允许新建(self):
        r = rule("new", create=True, set={"data.appConfigs.brandNew": "1"})
        res = ov.apply_overrides([r], "GET", "h", "/getAppConfigs", 200, APP_CONFIG)
        self.assertEqual(["new"], res.applied)

    def test_响应不是JSON时整条不生效(self):
        r = rule("x", set={"a.b": 1})
        res = ov.apply_overrides([r], "GET", "h", "/getAppConfigs", 200, "<html>nope</html>")
        self.assertEqual([], res.applied)
        self.assertIn("不是 JSON", res.problems[0])

    def test_响应体为空时整条不生效(self):
        r = rule("x", set={"a.b": 1})
        res = ov.apply_overrides([r], "GET", "h", "/getAppConfigs", 200, "")
        self.assertEqual([], res.applied)
        self.assertTrue(res.problems)

    def test_数组越界不算改到(self):
        r = rule("x", set={"data.list[9].name": "z"})
        res = ov.apply_overrides([r], "GET", "h", "/getAppConfigs", 200, APP_CONFIG)
        self.assertEqual([], res.applied)

    def test_生效的规则必须留痕(self):
        # 人看到一个诡异的响应，第一个该能回答的问题是「这是不是平台改的」
        r = rule("sysweb-off", set={"data.appConfigs.myTabSysWebSwitch": "0"})
        res = ov.apply_overrides([r], "GET", "h", "/getAppConfigs", 200, APP_CONFIG)
        self.assertIn("sysweb-off", res.applied)


class LoadRulesTest(unittest.TestCase):

    def _write(self, tmp, doc):
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(doc, f)
        return tmp

    def setUp(self):
        # 用系统临时目录，不往仓库里写东西——测试留下的垃圾文件迟早会被提交进去
        self.dir = tempfile.mkdtemp(prefix="apiov-")
        self.tmp = os.path.join(self.dir, "rules.json")

    def tearDown(self):
        shutil.rmtree(self.dir, ignore_errors=True)

    def test_没有规则文件就是没有规则(self):
        rules, problems = ov.load_rules(os.path.join(self.dir, "_nope.json"))
        self.assertEqual([], rules)
        self.assertEqual([], problems, "文件不存在是正常情况，不该报问题")

    def test_文件坏了不影响抓包只报原因(self):
        with open(self.tmp, "w", encoding="utf-8") as f:
            f.write("{ not json")
        rules, problems = ov.load_rules(self.tmp)
        self.assertEqual([], rules)
        self.assertTrue(problems)

    def test_过期的规则一律不生效(self):
        # 忘了关的改写规则会污染之后所有测试，而那种污染没有任何迹象
        self._write(self.tmp, {"expiresAt": time.time() - 10,
                               "rules": [rule("a", set={"x": 1})]})
        rules, problems = ov.load_rules(self.tmp)
        self.assertEqual([], rules)
        self.assertIn("过期", problems[0])

    def test_没写过期时间就按默认TTL(self):
        self._write(self.tmp, {"rules": [rule("a", set={"x": 1})]})
        rules, problems = ov.load_rules(self.tmp)
        self.assertEqual(1, len(rules))
        self.assertEqual([], problems)

    def test_没有id的规则被丢掉(self):
        self._write(self.tmp, {"rules": [{"match": {"path": "/x"}, "set": {"a": 1}}]})
        rules, problems = ov.load_rules(self.tmp)
        self.assertEqual([], rules)
        self.assertIn("id", problems[0])


if __name__ == "__main__":
    unittest.main(verbosity=1)
