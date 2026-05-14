package moe.lolosia.web.util.spring

import io.milvus.client.MilvusServiceClient

interface MilvusServiceClientProvider {
    val client: MilvusServiceClient
}

fun MilvusServiceClientProvider(block: () -> MilvusServiceClient) = object : MilvusServiceClientProvider {
    override val client: MilvusServiceClient get() = block()
}