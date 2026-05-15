FROM eclipse-temurin:21-jre

LABEL org.opencontainers.image.authors="Lolosia"

WORKDIR /app

# 配置时区
ENV TZ=Asia/Shanghai

#复制fat.jar到镜像内app目录下
ADD ./application.yaml /app/
ADD ./*-fat.jar /app/

RUN mv *-fat.jar app.jar

#程序启动脚本
CMD java -jar app.jar --no-gui
