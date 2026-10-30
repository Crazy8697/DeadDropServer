#!/bin/sh
# Container entrypoint. The container starts as root only so this script can read the root-only settings file, mounted
# read-only at /run/secrets/deaddrop.env. It exports the DEADDROP_* settings and then setpriv (util-linux, in the base
# image) switches to the deaddrop user before Java starts, so the server never runs as root. Admin commands go through
# here too: docker exec deaddrop-server deaddrop-entrypoint pair-code "Phone". Nothing from the file is ever printed.
set -eu
env_file=${DEADDROP_ENV_FILE:-/run/secrets/deaddrop.env}
run_as=${DEADDROP_RUN_AS:-983:976}
uid=${run_as%%:*}
gid=${run_as##*:}
case "$uid:$gid" in *[!0-9:]* | :* | *:) echo "entrypoint: DEADDROP_RUN_AS must be uid:gid" >&2; exit 2 ;; esac
if [ "$uid" -eq 0 ] || [ "$gid" -eq 0 ]; then echo "entrypoint: refusing to run the server as root" >&2; exit 2; fi
if [ "$(id -u)" -ne 0 ]; then echo "entrypoint: must start as root to read $env_file" >&2; exit 2; fi
if [ ! -r "$env_file" ]; then echo "entrypoint: $env_file is missing" >&2; exit 2; fi
while IFS= read -r line || [ -n "$line" ]; do
    case "$line" in '' | '#'*) continue ;; esac
    key=${line%%=*}
    case "$key" in
        DEADDROP_*) ;;
        *) echo "entrypoint: skipped a line that is not a DEADDROP_ setting" >&2; continue ;;
    esac
    case "$key" in *[!A-Z_]*) echo "entrypoint: bad setting name" >&2; exit 2 ;; esac
    export "$key=${line#*=}"
done < "$env_file"
export HOME=/tmp
exec setpriv --reuid="$uid" --regid="$gid" --clear-groups --inh-caps=-all --no-new-privs -- /opt/deaddrop-server/bin/deaddrop-server "$@"
