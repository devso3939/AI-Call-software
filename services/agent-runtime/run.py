# OpenCall agent runtime — local-first AI voice pipeline (Master Prompt Phase 4 / §31-§35).

import argparse
import sys

from opencall_agent.health import run_health_server
from opencall_agent.livekit_worker import available, run_worker


def main() -> int:
    parser = argparse.ArgumentParser(description="OpenCall AI agent runtime")
    parser.add_argument("--room", default=None, help="LiveKit room name to join (otherwise auto mode)")
    parser.add_argument("--health-port", type=int, default=4100)
    args = parser.parse_args()

    run_health_server(port=args.health_port)

    if not available():
        print(
            "[agent] livekit SDK not installed — running in degraded health-report mode.\n"
            "[agent] Install with: pip install -r requirements.txt  (then heavy extras per README)",
            file=sys.stderr,
        )
        try:
            import time

            while True:
                time.sleep(60)
        except KeyboardInterrupt:
            return 0

    try:
        run_worker(room_name=args.room)
    except KeyboardInterrupt:
        return 0
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
