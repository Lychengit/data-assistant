# 沙箱执行镜像（§18.4.4）：脚本在这里面跑，容器无网卡、无凭据、只读根文件系统。
#
# 为什么要把依赖烘进镜像而不是运行时 pip install：容器是 network=none（结构性不可达），
# 运行期根本装不了依赖。所以"这个技能要什么库"必须在构建期就定下来——这也正是
# "依赖清单要随技能一起审"的原因：容器里能用的东西，等于技能能用的东西。
#
# 为什么基于 python:3.12-slim 而不是 ubuntu + apt install python3：
# 后者带进一大堆用不上的包，镜像更大、可攻击面更宽；这份镜像只需要"能跑 openpyxl"。
FROM python:3.12-slim

# openpyxl：xlsx 读写。版本钉死——脚本的行为要可复现（同一份输入必须产出同一份表）。
RUN pip install --no-cache-dir openpyxl==3.1.5

# 不写 .pyc：技能目录是只读挂载，写了也是白写，还会在 stderr 上多出无意义的告警。
ENV PYTHONDONTWRITEBYTECODE=1
# 不缓冲 stdout：宿主代理按行读结果，缓冲会让"跑完了"晚几秒才可见。
ENV PYTHONUNBUFFERED=1

# 非 root 运行（容器里再加固一层：docker run 时还会带 --user 1000:1000 / --cap-drop ALL）。
#
# 为什么还要把工作区**预先建好并交给 1000**：agent-service 的内置沙箱（H-05）起了容器之后会执行
# `mkdir -p /workspace` 来准备工作区；默认用户不是 root 又对 / 没有写权限时，这一步会
# Permission denied，整轮对话直接 INTERNAL。预先建好之后 `mkdir -p` 是幂等空操作，
# 于是这份镜像既能给宿主代理用（仍显式 --user 1000:1000），也能直接当 AGENT_SANDBOX_IMAGE。
RUN mkdir -p /workspace && chown 1000:1000 /workspace
USER 1000:1000

WORKDIR /tmp