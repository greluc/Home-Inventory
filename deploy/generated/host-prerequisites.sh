# GENERATED FROM ../services.yaml — DO NOT EDIT.
#
# Sourced by setup.sh. Each entry is a setting a rootless container cannot
# make for itself, so it is a one-time root action at install time and the
# only sensible thing the setup script can do about it is refuse to
# continue with a clear sentence (REQ-NFR-060).

# The profile decides which checks apply: vm.max_map_count is OpenSearch's,
# and OpenSearch does not run in `minimal`. Demanding it there would teach
# an operator that the script's output is advisory.
check_host_prerequisites() {
    profile=${1:-minimal}
    missing=0
    # required by: opensearch (profiles: standard, ha)
    case "$profile" in standard|ha)
    actual=$(sysctl -n vm.max_map_count 2>/dev/null || echo 0)
    if [ "$actual" -lt 262144 ]; then
        echo "MISSING: vm.max_map_count is $actual, and opensearch needs at least 262144." >&2
        echo "         It is a HOST setting; a rootless container cannot set it." >&2
        echo "         Fix: echo vm.max_map_count=262144 | sudo tee /etc/sysctl.d/99-home-inv.conf" >&2
        echo "              sudo sysctl --system" >&2
        missing=1
    fi
    ;; esac
    return $missing
}
