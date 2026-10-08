FROM ubuntu:24.04
RUN apt-get update -qq && apt-get install -y -qq nftables python3 iproute2 && rm -rf /var/lib/apt/lists/*
COPY systemd-fixture.py /fixture/systemd-fixture.py
RUN ln -sf /fixture/systemd-fixture.py /usr/bin/systemd-run && ln -sf /fixture/systemd-fixture.py /usr/bin/systemctl
CMD ["sleep", "3600"]
