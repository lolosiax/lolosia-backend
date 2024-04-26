#!/usr/bin/env bash

declare name='lolosia-backend'
declare user=$(users)
echo "当前用户为：${user}"

echo '停止容器:' ${name}
docker stop ${name}

echo '删除容器:' ${name}
docker rm ${name}


declare imageId=$(docker images -q --filter "reference=$name")

# 判断旧镜像是否存在
if [ -n "$imageId" ];then

  declare imageId2=$(docker images -q --filter "reference=$name:old")

  # 判断之前是否还有其他的旧镜像
  if [ -n "$imageId2" ];then
    # 删除这个旧镜像
    docker rmi "$imageId2"
  fi

  # 修改旧镜像的名字为 名字:old
  docker tag "$imageId" "$name:old"
fi

echo "构建新版镜像: $name..."
# 拷贝 application.yml
cp /home/lolosia-web/application.yml ./
docker build -t "$name" .

if [ -n "$imageId" ];then
  #删除旧的镜像
  echo "删除镜像: $name:old"
  docker rmi "$imageId"
fi

echo "启动新版容器: $name..."

mkdir -p /home/lolosia-web/work
docker run -p 58801:58801 -d --restart=always -v /home/lolosia-web/work:/app/work --name ${name} ${name}