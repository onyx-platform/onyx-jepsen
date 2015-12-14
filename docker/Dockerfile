FROM kojiromike/dind
MAINTAINER jake@apache.org

ENV JEPSEN_GIT_URL http://github.com/aphyr/jepsen
ENV LEIN_ROOT true

#
# Jepsen dependencies
#

RUN apt-get -y -q update && \
    apt-get -y -q install software-properties-common && \
    apt-get install -y git gnuplot wget

RUN add-apt-repository -y ppa:webupd8team/java
RUN apt-get install -y software-properties-common
RUN apt-get update

# Auto-accept the Oracle JDK license
RUN echo oracle-java8-installer shared/accepted-oracle-license-v1-1 select true | sudo /usr/bin/debconf-set-selections

RUN apt-get install -y oracle-java8-installer

RUN apt-get clean
RUN apt-get autoclean
RUN apt-get autoremove

RUN cd / && wget https://raw.githubusercontent.com/technomancy/leiningen/stable/bin/lein && mv /lein /usr/bin 
RUN chmod +x /usr/bin/lein

RUN git clone $JEPSEN_GIT_URL /jepsen
RUN cd /jepsen/jepsen && git checkout c07da100f4dfc7393f6f86f6f91d0c0b53847e9f && lein install

ADD ./build-dockerized-jepsen.sh /usr/local/bin/build-dockerized-jepsen.sh
RUN chmod +x /usr/local/bin/build-dockerized-jepsen.sh

ADD ./bashrc /root/.bashrc
