#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
RAG 检索评测脚本（Python3 标准库实现，无需 pip 安装任何依赖）

用法:
    python eval.py --base http://localhost:8080 --token <JWT> --kb 1 --k 5

标注集格式（eval_set.jsonl，每行一条 JSON）:
    {"question": "员工年假有多少天？", "docId": 1, "chunkIndex": 12}
    - question:    测试问题
    - docId:       预期命中的文档 id（对应 /api/documents 列表里的 id）
    - chunkIndex:  预期命中的片段序号

评判标准: 调 /api/kb/{id}/search 接口（只检索不生成），
返回 Top-K 片段中存在 (docId, chunkIndex) 与标注一致即判为命中。
"""
import argparse
import json
import sys
import time
import urllib.error
import urllib.request


def search(base_url, token, kb_id, question, timeout=60):
    url = "%s/api/kb/%s/search" % (base_url.rstrip("/"), kb_id)
    payload = json.dumps({"question": question}).encode("utf-8")
    req = urllib.request.Request(url, data=payload, method="POST", headers={
        "Content-Type": "application/json",
        "Authorization": "Bearer %s" % token,
    })
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8"))["data"]


def main():
    parser = argparse.ArgumentParser(description="RAG 检索评测")
    parser.add_argument("--base", default="http://localhost:8080")
    parser.add_argument("--token", required=True, help="登录接口返回的 JWT")
    parser.add_argument("--kb", type=int, required=True, help="知识库 id")
    parser.add_argument("--k", type=int, default=5, help="Top-K 命中判定")
    parser.add_argument("--file", default="eval_set.jsonl", help="标注集路径")
    args = parser.parse_args()

    try:
        sys.stdout.reconfigure(encoding="utf-8")  # Windows 控制台中文输出
    except AttributeError:
        pass

    with open(args.file, encoding="utf-8") as f:
        items = [json.loads(line) for line in f if line.strip()]

    hits, total, max_sims, latencies = 0, 0, [], []
    for item in items:
        question = item["question"]
        start = time.time()
        try:
            data = search(args.base, args.token, args.kb, question)
            sources = data.get("sources", [])[:args.k]
            ok = any(s.get("docId") == item["docId"] and s.get("chunkIndex") == item["chunkIndex"]
                     for s in sources)
        except urllib.error.HTTPError as e:
            print("[ERROR] HTTP %s %s -> %s" % (e.code, question, e.read().decode("utf-8", "ignore")[:200]))
            continue
        except urllib.error.URLError as e:
            print("[ERROR] 网络异常 %s -> %s" % (question, e.reason))
            continue

        total += 1
        hits += 1 if ok else 0
        max_sim = data.get("maxSimilarity", 0)
        latency = (time.time() - start) * 1000
        max_sims.append(max_sim)
        latencies.append(latency)
        print("[%s] %s  (maxSim=%.3f, %.0fms)" % ("HIT" if ok else "MISS", question, max_sim, latency))

    if total == 0:
        print("没有可评测的数据，请检查标注集与接口连通性")
        return

    print("\n========== 评测报告 ==========")
    print("数据量: %d, 命中数: %d" % (total, hits))
    print("Hit@%d: %.1f%%" % (args.k, hits * 100.0 / total))
    print("平均 maxSimilarity: %.3f（调 rag.retrieval.min-similarity 兜底阈值的参考）" % (sum(max_sims) / len(max_sims)))
    print("平均检索耗时: %.0f ms" % (sum(latencies) / len(latencies)))


if __name__ == "__main__":
    main()
