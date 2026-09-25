#!/usr/bin/env bash
# G4 发布门禁执行器（W31 · w27-next-plan §5）
#
# 逐行执行 java-server/docs/g4-release-gate.tsv 中的门禁命令，收集日志与 shadowdiff 报告，
# 最后在报告目录生成中文 Markdown 汇总 g4-release-gate.md，并以退出码给出结论：
#   0 = 全部 blocking 行达到预期（注意：这**不等于** G4 签发，只等于本地 Java↔Java 门禁全绿）
#   1 = 有 blocking 行未达预期
#   2 = 用法 / 环境错误（缺 JDK、缺 Maven、缺清单等）
#
# 用法：
#   java-server/scripts/g4-release-gate.sh [--report-dir DIR] [--only ID[,ID...]]
#                                          [--skip-maven] [--jar PATH] [--list]
#
# 约定：清单是唯一权威来源。新增门禁场景请改 TSV，不要改本脚本里的场景列表
#      （G4ReleaseGateTest 会校验清单格式与参数向量的合法性）。
set -uo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/../.." && pwd)"
manifest="$repo_root/java-server/docs/g4-release-gate.tsv"

report_dir="$repo_root/java-server/target/g4-release-gate"
only=""
skip_maven=0
jar=""
list_only=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --report-dir) report_dir="${2:?--report-dir requires a value}"; shift 2 ;;
    --only) only="${2:?--only requires a value}"; shift 2 ;;
    --jar) jar="${2:?--jar requires a value}"; shift 2 ;;
    --skip-maven) skip_maven=1; shift ;;
    --list) list_only=1; shift ;;
    -h|--help) sed -n '2,25p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "[g4-gate] 未知参数: $1" >&2; exit 2 ;;
  esac
done

[[ -f "$manifest" ]] || { echo "[g4-gate] 找不到清单: $manifest" >&2; exit 2; }

if [[ -z "$jar" ]]; then
  jar="$repo_root/java-server/shadowdiff/target/mir2-shadowdiff.jar"
fi
mkdir -p "$report_dir"
summary="$report_dir/g4-release-gate.md"

rows_id=(); rows_kind=(); rows_blocking=(); rows_expect=(); rows_cmd=(); rows_artifact=(); rows_scope=()
while IFS=$'\t' read -r id kind blocking expect command artifact scope notes; do
  [[ -z "${id:-}" || "$id" == \#* || "$id" == "id" ]] && continue
  rows_id+=("$id"); rows_kind+=("$kind"); rows_blocking+=("$blocking")
  rows_expect+=("$expect"); rows_cmd+=("$command"); rows_artifact+=("$artifact"); rows_scope+=("$scope")
done < "$manifest"

if (( ${#rows_id[@]} == 0 )); then
  echo "[g4-gate] 清单中没有可执行行" >&2; exit 2
fi

if (( list_only )); then
  for index in "${!rows_id[@]}"; do
    printf '%-26s %-11s %-7s %s\n' "${rows_id[$index]}" "${rows_kind[$index]}" \
      "${rows_expect[$index]}" "${rows_scope[$index]}"
  done
  exit 0
fi

selected() {
  [[ -z "$only" ]] && return 0
  local candidate
  IFS=',' read -ra wanted <<<"$only"
  for candidate in "${wanted[@]}"; do [[ "$candidate" == "$1" ]] && return 0; done
  return 1
}

command -v java >/dev/null 2>&1 || { echo "[g4-gate] 环境缺少 java（需要 JDK 21）" >&2; exit 2; }

started_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
results_id=(); results_status=(); results_exit=(); results_seconds=()
failures=0
skipped=0

for index in "${!rows_id[@]}"; do
  id="${rows_id[$index]}"; kind="${rows_kind[$index]}"
  expect="${rows_expect[$index]}"; blocking="${rows_blocking[$index]}"
  if ! selected "$id"; then continue; fi
  if [[ "$kind" == "maven" && $skip_maven -eq 1 ]]; then
    results_id+=("$id"); results_status+=("SKIP"); results_exit+=("-"); results_seconds+=("0")
    skipped=$((skipped + 1))
    continue
  fi
  if [[ "$kind" == "maven" ]] && ! command -v mvn >/dev/null 2>&1; then
    echo "[g4-gate] 环境缺少 mvn，无法执行 $id（如需跳过请显式 --skip-maven）" >&2
    exit 2
  fi
  if [[ "$kind" == "shadowdiff" && ! -f "$jar" ]]; then
    echo "[g4-gate] 找不到 shadowdiff fat JAR: $jar" >&2
    echo "[g4-gate] 先执行 mvn -f java-server/pom.xml -pl shadowdiff -am -DskipTests package" >&2
    exit 2
  fi

  cmd="${rows_cmd[$index]}"
  cmd="${cmd//"{{jar}}"/$jar}"
  cmd="${cmd//"{{report}}"/$report_dir}"
  cmd="${cmd//"{{repo}}"/$repo_root}"
  log="$report_dir/$id.log"

  echo
  echo "[g4-gate] ($((index + 1))/${#rows_id[@]}) $id — 期望 $expect"
  echo "[g4-gate] \$ $cmd"
  start=$(date +%s)
  # shellcheck disable=SC2086
  eval "$cmd" >"$log" 2>&1
  code=$?
  elapsed=$(( $(date +%s) - start ))

  case "$expect" in
    exit-0) [[ $code -eq 0 ]] && verdict=PASS || verdict=FAIL ;;
    exit-1) [[ $code -eq 1 ]] && verdict=PASS || verdict=FAIL ;;
    *) echo "[g4-gate] 清单 expect 非法: $expect" >&2; exit 2 ;;
  esac
  [[ "$verdict" == FAIL && "$blocking" != "yes" ]] && verdict=WARN

  results_id+=("$id"); results_status+=("$verdict")
  results_exit+=("$code"); results_seconds+=("$elapsed")
  [[ "$verdict" == FAIL ]] && failures=$((failures + 1))
  echo "[g4-gate] -> $verdict (exit $code, ${elapsed}s, 日志 $log)"
done

{
  echo "# G4 发布门禁跑批汇总"
  echo
  echo "- 开始时间（UTC）：$started_at"
  echo "- 结束时间（UTC）：$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "- 仓库：$repo_root（\`$(git -C "$repo_root" rev-parse --short HEAD 2>/dev/null || echo unknown)\`）"
  echo "- 清单：\`java-server/docs/g4-release-gate.tsv\`"
  echo "- 报告目录：$report_dir"
  echo
  echo "| 门禁行 | 结论 | 退出码 | 期望 | 耗时(s) | 日志 |"
  echo "|---|---|---|---|---|---|"
  for index in "${!results_id[@]}"; do
    id="${results_id[$index]}"
    expect="exit-0"
    for probe in "${!rows_id[@]}"; do
      [[ "${rows_id[$probe]}" == "$id" ]] && expect="${rows_expect[$probe]}"
    done
    echo "| $id | ${results_status[$index]} | ${results_exit[$index]} | $expect | ${results_seconds[$index]} | $id.log |"
  done
  echo
  echo "失败行数：$failures；跳过：$skipped。"
  echo
  echo "> **红线：** 本汇总全绿只代表本地 Java↔Java 确定性门禁通过，**不等于 G4 签发**。"
  echo "> G4 还需要真实 mir2.exe / Delphi 服务端外部基线，以及技能、NPC 脚本、行会、攻城、"
  echo "> 交易的缺口收口——逐项状态见 \`java-server/docs/g4-capability-matrix.tsv\`。"
} >"$summary"

echo
echo "[g4-gate] 汇总写入 $summary"
if (( failures > 0 )); then
  echo "[g4-gate] 结论：门禁未通过（$failures 行失败）"
  exit 1
fi
echo "[g4-gate] 结论：本地门禁全绿（注意：非 G4 签发）"
exit 0
