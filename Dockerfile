#制定java镜像的版本
FROM ubuntu:24.04

#声明作者
LABEL org.opencontainers.image.authors="Lolosia"

#进入到镜像内app目录下面，类似cd
WORKDIR /app/

RUN sed -i 's/archive.ubuntu.com/mirrors.tuna.tsinghua.edu.cn/' /etc/apt/sources.list.d/ubuntu.sources \
    && apt update && apt upgrade -y \
    && apt install -y openjdk-21-jdk locales \
    && sed -i 's/# en_US.UTF-8 UTF-8/en_US.UTF-8 UTF-8/' /etc/locale.gen \
    && locale-gen

ENV LANG en_US.UTF-8
ENV LANGUAGE en_US:en
ENV LC_ALL en_US.UTF-8
ENV TZ Asia/Shanghai
RUN ln -sf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime

#复制fat.jar到镜像内app目录下
ADD ./application.yaml /app/
ADD ./*-fat.jar /app/

RUN mv *-fat.jar app.jar

#程序启动脚本
CMD java -jar app.jar --no-gui
