package top.lolosia.web.util.elastic

import co.elastic.clients.elasticsearch.ElasticsearchClient
import top.lolosia.web.util.spring.ApplicationContextProvider
import org.springframework.beans.factory.getBean

fun ApplicationContextProvider.update(database: String, entity: ElasticsearchModel){
    val client = applicationContext.getBean<ElasticsearchClient>()
    client.update<ElasticsearchModel, ElasticsearchModel>({ u ->
        u.index(database)
            .id(entity.id.toString())
            .doc(entity)
    }, entity::class.java)
}