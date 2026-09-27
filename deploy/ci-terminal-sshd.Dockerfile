FROM alpine:3.22

RUN apk add --no-cache openssh \
    && adduser -D -s /bin/sh ci \
    && echo 'ci:stage20-ci-only-password' | chpasswd \
    && ssh-keygen -t rsa -b 2048 -N '' -f /etc/ssh/ssh_host_rsa_key

RUN printf '%s\n' \
    'Port 22' \
    'ListenAddress 0.0.0.0' \
    'HostKey /etc/ssh/ssh_host_rsa_key' \
    'PermitRootLogin no' \
    'PasswordAuthentication yes' \
    'KbdInteractiveAuthentication no' \
    'UsePAM no' \
    'AllowUsers ci' \
    'StrictModes no' \
    > /etc/ssh/sshd_config \
    && /usr/sbin/sshd -t

EXPOSE 22
CMD ["/usr/sbin/sshd", "-D", "-e"]
