"""Emit the retained full personal cards with exact original v041 status badges."""
from pathlib import Path
import argparse
from v041_video_status_personal_consumers import generate

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    generate(args.repo, args.output)
