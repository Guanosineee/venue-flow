#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
压测脚本（stdlib 实现，零依赖）：
  模式一 秒杀：python bench.py seckill --tokens tokens.txt --ticket 1
              （每个用户发一单；结束后自动调 /order/stat 对账，超卖一眼可见）
  模式二 缓存：python bench.py venue --id 1
              （首轮冷查询 vs 预热后查询的延迟对比）
  模式三 QPS：python bench.py qps --path /venue/1 --threads 16 --duration 10
              （keep-alive 长连接持续压测，测饱和吞吐；--path /order/seckill/1 时需 --tokens）
"""
import argparse
import http.client
import json
import statistics
import threading
import time
import urllib.error
import urllib.request

BASE = "http://localhost:8080"
HOST, PORT = "localhost", 8080


def bench_qps(path, tokens, threads, duration):
    stop = threading.Event()
    lock = threading.Lock()
    ok_count = err_count = 0
    latencies = []

    def worker(token):
        nonlocal ok_count, err_count
        conn = http.client.HTTPConnection(HOST, PORT, timeout=10)   # keep-alive 长连接
        local_lat = []
        local_ok = local_err = 0
        while not stop.is_set():
            headers = {"authorization": token} if token else {}
            t0 = time.perf_counter()
            try:
                conn.request("POST" if path.startswith("/order") else "GET", path, headers=headers)
                resp = conn.getresponse()
                resp.read()
                if resp.status == 200:
                    local_ok += 1
                else:
                    local_err += 1
            except Exception:
                local_err += 1
                try:
                    conn.close()
                    conn = http.client.HTTPConnection(HOST, PORT, timeout=10)
                except Exception:
                    return
            local_lat.append((time.perf_counter() - t0) * 1000)
        try:
            conn.close()
        except Exception:
            pass
        with lock:
            ok_count += local_ok
            err_count += local_err
            latencies.extend(local_lat)

    ts = []
    if tokens:
        with open(tokens, encoding="utf-8") as f:
            all_tokens = [line.strip() for line in f if line.strip()]
        for i in range(threads):
            ts.append(threading.Thread(target=worker, args=(all_tokens[i % len(all_tokens)],)))
    else:
        ts = [threading.Thread(target=worker, args=(None,)) for _ in range(threads)]
    t0 = time.perf_counter()
    for t in ts:
        t.start()
    time.sleep(duration)
    stop.set()
    for t in ts:
        t.join()
    wall = time.perf_counter() - t0

    latencies.sort()
    total = ok_count + err_count
    print("=" * 56)
    print(f"目标     : POST {path}" if path.startswith("/order") else f"目标     : GET  {path}")
    print(f"线程x连接: {threads} (keep-alive)   时长: {duration}s")
    print(f"请求总数 : {total}   成功 {ok_count}   失败 {err_count}")
    print(f"吞吐     : {total/wall:.0f} QPS")
    if latencies:
        p = lambda q: latencies[min(int(len(latencies)*q), len(latencies)-1)]
        print(f"延迟 ms  : avg={statistics.mean(latencies):.1f}  p50={p(0.5):.1f}  "
              f"p95={p(0.95):.1f}  p99={p(0.99):.1f}")
    print("=" * 56)


def call(method, path, token=None):
    url = BASE + path
    req = urllib.request.Request(url, method=method)
    if token:
        req.add_header("authorization", token)
    t0 = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            body = json.loads(resp.read().decode())
            return body, (time.perf_counter() - t0) * 1000
    except urllib.error.HTTPError as e:
        body = json.loads(e.read().decode())
        return body, (time.perf_counter() - t0) * 1000


def bench_seckill(tokens, ticket, path):
    barrier = threading.Barrier(len(tokens))
    lock = threading.Lock()
    stats = {"ok": 0, "soldout": 0, "dup": 0, "other": 0}
    latencies = []

    def worker(token):
        barrier.wait()   # 所有线程同时起跑，洪峰就在这一瞬间
        body, ms = call("POST", f"{path}", token)
        with lock:
            latencies.append(ms)
            if body["code"] == 0:
                stats["ok"] += 1
            elif "售罄" in body.get("msg", ""):
                stats["soldout"] += 1
            elif "重复" in body.get("msg", "") or "抢过" in body.get("msg", ""):
                stats["dup"] += 1
            else:
                stats["other"] += 1

    threads = [threading.Thread(target=worker, args=(t,)) for t in tokens]
    t0 = time.perf_counter()
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    wall = time.perf_counter() - t0

    print("=" * 52)
    print(f"并发用户数 : {len(tokens)}")
    print(f"下单成功   : {stats['ok']}")
    print(f"售罄拦截   : {stats['soldout']}   重复拦截: {stats['dup']}   其他: {stats['other']}")
    print(f"总耗时     : {wall:.2f}s   吞吐 ≈ {len(tokens)/wall:.0f} req/s")
    if latencies:
        latencies.sort()
        print(f"延迟 ms    : avg={statistics.mean(latencies):.0f}  "
              f"p95={latencies[int(len(latencies)*0.95)-1]:.0f}")
    print("-" * 52)
    body, _ = call("GET", f"/order/stat/{ticket}")
    print("对账(stat) :", json.dumps(body["data"], ensure_ascii=False))
    print("=" * 52)


def bench_venue(vid, rounds):
    # 首轮：清缓存冷启动（真实回源），之后每轮都是缓存命中
    call("DELETE", f"/venue/cache/{vid}")
    for r in range(rounds):
        latencies = []
        for _ in range(100):
            _, ms = call("GET", f"/venue/{vid}")
            latencies.append(ms)
        latencies.sort()
        tag = "冷(回源DB)" if r == 0 else "热(缓存命中)"
        print(f"第{r+1}轮[{tag}] avg={statistics.mean(latencies):.1f}ms "
              f"p50={latencies[49]:.1f}ms p95={latencies[94]:.1f}ms")


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="mode", required=True)

    s = sub.add_parser("seckill")
    s.add_argument("--tokens", default="tokens.txt")
    s.add_argument("--ticket", type=int, default=1)
    s.add_argument("--naive", action="store_true", help="打翻车版接口(超卖复现)")

    v = sub.add_parser("venue")
    v.add_argument("--id", type=int, default=1)
    v.add_argument("--rounds", type=int, default=3)

    q = sub.add_parser("qps")
    q.add_argument("--path", default="/venue/1")
    q.add_argument("--tokens", default=None, help="打需登录接口时提供 token 文件")
    q.add_argument("--threads", type=int, default=8)
    q.add_argument("--duration", type=int, default=10)

    args = ap.parse_args()
    if args.mode == "seckill":
        with open(args.tokens, encoding="utf-8") as f:
            tokens = [line.strip() for line in f if line.strip()]
        path = f"/order/seckill-naive/{args.ticket}" if args.naive else f"/order/seckill/{args.ticket}"
        bench_seckill(tokens, args.ticket, path)
    elif args.mode == "qps":
        bench_qps(args.path, args.tokens, args.threads, args.duration)
    else:
        bench_venue(args.id, args.rounds)


if __name__ == "__main__":
    main()
