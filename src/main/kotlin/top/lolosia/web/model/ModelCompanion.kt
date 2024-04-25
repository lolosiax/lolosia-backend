package top.lolosia.web.model

import io.ebean.Database

interface ModelCompanion {
    val database: Database
}