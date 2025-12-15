#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生成压测用户 token。
前提：application.yml 里 venue-flow.unsafe=true（验证码回显模式，压测脚本才能拿到码）。
用法：python gen_tokens.py --n 200 --out tokens.txt
产出：每行一个 token（bench.py 直接吃这个文件）
"""
import argparse
import json
import urllib.parse
import urllib.request

BASE = "http://localhost:8080"


def post(path, params):
    url = BASE + path + "?" + urllib.parse.urlencode(params)
    req = urllib.request.Request(url, method="POST")
    with urllib.request.urlopen(req, timeout=10) as resp:
        return json.loads(resp.read().decode())


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--n", type=int, default=200, help="用户数")
    ap.add_argument("--out", default="tokens.txt")
    args = ap.parse_args()

    tokens = []
    for i in range(args.n):
        phone = f"138{i:08d}"   # 138 + 8位 = 11位手机号
        r1 = post("/user/code", {"phone": phone})
        if r1["code"] != 0:
            print(f"[fail] 发码失败: {r1}")
            continue
        code = r1["data"]
        r2 = post("/user/login", {"phone": phone, "code": code})
        if r2["code"] != 0:
            print(f"[fail] 登录失败: {r2}")
            continue
        tokens.append(r2["data"])

    with open(args.out, "w", encoding="utf-8") as f:
        f.write("\n".join(tokens))
    print(f"完成：{len(tokens)}/{args.n} 个用户 token 写入 {args.out}")


if __name__ == "__main__":
    main()
