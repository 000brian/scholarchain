# AWS Emulator image for local ScholarChain emulator tests (Phase 16, decision 5.3).
# Owner: build-engineer. Source: the AWS Emulator user guide, "Getting started with Docker",
# section "To build the AWS Emulator image"
# (https://docs.aws.amazon.com/emulator/latest/userguide/getting-started-docker.html). The
# instructions below are that documented Dockerfile verbatim, with the one substitution the page
# documents for Apple silicon and other Arm processors (aws-emulator-x86_64.rpm replaced by
# aws-emulator-aarch64.rpm). Additions: `--platform=linux/arm64` on FROM, `EXPOSE 9297`, and these
# comments. Nothing else: no extra packages, no user creation, no entrypoint flags.
#
# Binding: the documented Dockerfile binds with `ENV EMULATOR_BIND_ADDRESS=0.0.0.0` and a bare
# `ENTRYPOINT ["aws-emulator"]`. The work order (decision 5.3) worded this as "the documented
# entrypoint with `-b 0.0.0.0`"; its governing rule is "exactly the documented one", so the
# documented ENV binding replaces the `-b 0.0.0.0` wording (orchestrator decision, repair round
# P16-B-01). The emulator listens on all addresses inside the container only; the host side is
# limited to the loopback interface by `-p 127.0.0.1:9297:9297`. The container runs as UID 1000
# (non-root), as documented.
#
# Build and run (scripts/README.md emulator section, steps 2 and 5; run only by the human, never
# by an agent):
#   docker build -t scholarchain-emulator docker/emulator
#   docker image inspect scholarchain-emulator --format '{{.Architecture}}'   (must print arm64)
#   docker run --rm --memory=2g --memory-swap=2g -p 127.0.0.1:9297:9297 -e HOME="$HOME"
#     -v "$HOME/.aws/emulator:$HOME/.aws/emulator" scholarchain-emulator -p 9297 --telemetry off
# The run arguments (`-p 9297 --telemetry off`) follow the image name and are appended to the
# entrypoint, as the documentation's run command and telemetry page show.
#
# Version: the package is the documentation's LTS/latest build, not a pinned release, and the base
# image is a tag, not a digest, so the version the emulator prints when it starts must be recorded
# with the image ID at every run (E14). Two builds on different days may contain different
# emulator versions.
#
# Scope: this image is never pushed to any registry, has no tag in the dependency ledger, and is
# not part of the services build (sbt dockerStage/dockerLocal and scripts/package.sh use only
# docker/services/Dockerfile). Build identity: `docker` is one of the build-input paths in
# project/BuildIdentity.scala (and scripts/lib/identity.sh), so while this file is uncommitted
# every assembled jar records buildDirty=true, and the commit that adds or changes it becomes the
# jars' buildCommit. That is harmless now: no release is made in this phase, and the same
# commit also changes build.sbt and project/**, which are build inputs anyway.

FROM --platform=linux/arm64 public.ecr.aws/amazonlinux/amazonlinux:2023-minimal
ADD https://downloads.aws.com/aws-emulator/packages/aws-emulator/LTS/latest/aws-emulator-aarch64.rpm /tmp/aws-emulator.rpm
RUN rpm -i /tmp/aws-emulator.rpm && rm /tmp/aws-emulator.rpm \
    && mkdir -p /home/emulator && chown 1000 /home/emulator
USER 1000
ENV HOME=/home/emulator EMULATOR_BIND_ADDRESS=0.0.0.0
EXPOSE 9297
ENTRYPOINT ["aws-emulator"]
