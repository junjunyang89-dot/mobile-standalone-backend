#!/usr/bin/env node
/**
 * Cursor SDK 本机桥。Java 调：
 *   node ask.mjs < input.json
 *
 * 输入：
 *   { apiKey, cwd, message, agentId?, model?, mode? }
 *   mode: "ask"（默认只读）| "k8s"（可写 k8s-config）
 * 输出（stdout 单行 JSON）：
 *   { ok, agentId, runId, status, answer, error? }
 */
import { Agent, CursorAgentError } from "@cursor/sdk";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";

function readInput() {
  const arg = process.argv[2];
  if (arg && arg !== "-") {
    return JSON.parse(readFileSync(arg, "utf8"));
  }
  const raw = readFileSync(0, "utf8");
  if (!raw.trim()) {
    throw new Error("stdin 为空：请传入 JSON（apiKey/cwd/message）");
  }
  return JSON.parse(raw);
}

function out(obj) {
  process.stdout.write(JSON.stringify(obj) + "\n");
}

const ASK_PREFIX = [
  "【平台约束】你是测试平台里的代码与搭建顾问。",
  "只做只读分析：查代码、解释逻辑、说明怎么配置/搭建。",
  "不要改仓库文件、不要 git commit/push、不要开 PR、不要删文件。",
  "用中文简洁回答；涉及实现时给出仓库相对路径和关键行号/条件。",
  "",
  "【用户问题】",
].join("\n");

const K8S_PREFIX = [
  "【任务】公司测试环境 K8s 部署文件生成（Java/SpringBoot）。",
  "你要分析用户给的服务源码目录，对照已有 k8s-config 同类服务与技能模板，生成并写入 5 个文件。",
  "",
  "【落盘路径】",
  "- app 业务：k8s-config/app/test/<SERVICE_NAME>/",
  "- pvp 业务：k8s-config/pvp/test/<SERVICE_NAME>/",
  "五个文件：Dockerfile、Jenkinsfile、*-filebeat.yaml（或 filebeat.yaml）、application-test.properties、deploy.yaml",
  "",
  "【必读】",
  "1. 技能说明（二选一）：skills/k8s-java-test-deploy/SKILL.md 或 ~/.agents/skills/k8s-java-test-deploy/SKILL.md",
  "2. 模板：同目录下 templates/",
  "3. 现成样例：app_coding/k8s-config/app/test/ 下任一服务（如 app-engine、app-admin）",
  "",
  "【分析源码时确认】",
  "artifactId / WAR 名、SpringBoot profile=test、端口、日志目录、DB/Redis/Nacos 等（敏感值用占位符，不编造密码）",
  "",
  "【约束】",
  "- 可以创建/覆盖上述 5 个文件；不要 git commit/push；不要改业务源码",
  "- deploy.yaml 镜像保留 <IMAGE>:<IMAGE_TAG>",
  "- Dockerfile ADD 路径必须匹配真实 WAR",
  "- 完成后用中文回复：变量表、写入路径列表、Jenkins 创建要点（Job 指向 k8s-config 仓库 Script Path）",
  "",
  "【用户需求】",
].join("\n");

async function main() {
  const input = readInput();
  const apiKey = String(input.apiKey || process.env.CURSOR_API_KEY || "").trim();
  const cwd = resolve(String(input.cwd || "").trim() || process.cwd());
  const message = String(input.message || "").trim();
  const mode = String(input.mode || "ask").trim().toLowerCase() || "ask";
  const modelId = String(input.model || "default").trim() || "default";
  const model =
    modelId === "default" || modelId === "auto"
      ? { id: "default" }
      : { id: modelId };
  const agentIdIn = input.agentId ? String(input.agentId).trim() : "";
  const prefix = mode === "k8s" ? K8S_PREFIX : ASK_PREFIX;
  const prompt = agentIdIn ? message : `${prefix}\n${message}`;

  if (!apiKey) {
    out({ ok: false, error: "缺少 CURSOR_API_KEY / apiKey" });
    process.exit(1);
  }
  if (!message) {
    out({ ok: false, error: "message 不能为空" });
    process.exit(1);
  }

  const options = {
    apiKey,
    model,
    local: {
      cwd,
      settingSources: [],
    },
    // ask：禁改文件；k8s：允许 edit，仍禁 delete
    disallowedTools:
      mode === "k8s"
        ? ["delete", "applyAgentDiff"]
        : ["edit", "delete", "applyAgentDiff"],
  };

  let agent;
  try {
    agent = agentIdIn
      ? await Agent.resume(agentIdIn, options)
      : await Agent.create(options);

    const run = await agent.send(prompt, {
      local: { force: true },
    });
    const result = await run.wait();
    const answer =
      result.status === "finished"
        ? String(result.result || "").trim() || "（模型没有返回正文）"
        : `运行失败：${result.error?.message || result.status}`;

    out({
      ok: result.status === "finished",
      agentId: agent.agentId,
      runId: result.id,
      status: result.status,
      answer,
      mode,
      error: result.status === "finished" ? undefined : result.error?.message || result.status,
    });
    process.exit(result.status === "finished" ? 0 : 2);
  } catch (err) {
    const msg =
      err instanceof CursorAgentError
        ? `${err.message}${err.isRetryable ? "（可重试）" : ""}`
        : err?.message || String(err);
    out({
      ok: false,
      agentId: agent?.agentId || agentIdIn || "",
      error: msg,
    });
    process.exit(1);
  } finally {
    if (agent && typeof agent[Symbol.asyncDispose] === "function") {
      try {
        await agent[Symbol.asyncDispose]();
      } catch {
        /* ignore */
      }
    }
  }
}

main();
