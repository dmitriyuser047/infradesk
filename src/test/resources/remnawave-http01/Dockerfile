FROM infradesk-syn-test:ubuntu24
RUN apt-get update -qq && apt-get install -y -qq iptables openssl && rm -rf /var/lib/apt/lists/*
COPY docker-fixture.py /usr/local/bin/docker
RUN chmod 755 /usr/local/bin/docker /fixture/systemd-fixture.py
