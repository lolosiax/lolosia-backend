#制定java镜像的版本
FROM openjdk:17

#声明作者
LABEL org.opencontainers.image.authors="Lolosia"

#进入到镜像内app目录下面，类似cd
WORKDIR /app/

#复制fat.jar到镜像内app目录下
ADD ./application.yml /app/
ADD ./build/libs/*-fat.jar /app/

RUN mv *-fat.jar app.jar

#对外暴露的端口
EXPOSE 58801

#程序启动脚本
CMD java -jar app.jar
