#!/bin/sh

#gen sshkey
ssh-keygen -t rsa -N "" -f ~/.ssh/id_rsa

#start one node and install deps
docker run -d --name n1 -e ROOT_PASS="root" -e AUTHORIZED_KEYS="`cat ~/.ssh/id_rsa.pub`" tutum/debian:jessie
N1_IP=$(docker inspect --format '{{ .NetworkSettings.IPAddress }}' n1)

sleep 10

ssh -oStrictHostKeyChecking=no $N1_IP "rm /etc/apt/apt.conf.d/docker-clean && apt-get update && \
apt-get install -y sudo net-tools wget sysvinit-core sysvinit sysvinit-utils curl vim man man-db faketime unzip iptables iputils-ping logrotate zookeeper zookeeperd zookeeper-bin || \
apt-get install -y sudo net-tools wget sysvinit-core sysvinit sysvinit-utils curl vim man man-db faketime unzip iptables iputils-ping logrotate zookeeper zookeeperd zookeeper-bin || \
apt-get install -y sudo net-tools wget sysvinit-core sysvinit sysvinit-utils curl vim man man-db faketime unzip iptables iputils-ping logrotate zookeeper zookeeperd zookeeper-bin || \
apt-get install -y sudo net-tools wget sysvinit-core sysvinit sysvinit-utils curl vim man man-db faketime unzip iptables iputils-ping logrotate zookeeper zookeeperd zookeeper-bin || \
apt-get install -y sudo net-tools wget sysvinit-core sysvinit sysvinit-utils curl vim man man-db faketime unzip iptables iputils-ping logrotate zookeeper zookeeperd zookeeper-bin \
&& apt-get remove -y --purge --auto-remove systemd && echo 'deb http://ppa.launchpad.net/webupd8team/java/ubuntu trusty main' | tee /etc/apt/sources.list.d/webupd8team-java.list && echo 'deb-src http://ppa.launchpad.net/webupd8team/java/ubuntu trusty main' | tee -a /etc/apt/sources.list.d/webupd8team-java.list && apt-key adv --keyserver hkp://keyserver.ubuntu.com:80 --recv-keys EEA14886 && apt-key update && apt-get update && echo oracle-java8-installer shared/accepted-oracle-license-v1-1 select true | sudo /usr/bin/debconf-set-selections && apt-get install oracle-java8-installer -y --force-yes" 

docker export n1 > /root/jepsennode.tar
gzip /root/jepsennode.tar

killall docker
rm -f /var/run/docker.sock
