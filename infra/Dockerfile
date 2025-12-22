FROM ubuntu:22.04

# Install dependencies
RUN apt-get update && apt-get install -y \
    bubblewrap \
    curl \
    gnupg \
    ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# Install Bazel (using Bazelisk for version management is often easier, but we'll try direct bazel for now or bazelisk as 'bazel')
# Installing Bazelisk as 'bazel'
RUN curl -L https://github.com/bazelbuild/bazelisk/releases/download/v1.19.0/bazelisk-linux-amd64 -o /usr/bin/bazel \
    && chmod +x /usr/bin/bazel

# Create necessary directories
RUN mkdir -p /work/src /work/out

# Copy entrypoint
COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

ENTRYPOINT ["/entrypoint.sh"]
