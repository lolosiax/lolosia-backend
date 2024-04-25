package top.lolosia.web.service.system

import com.fasterxml.jackson.databind.json.JsonMapper
import top.lolosia.web.interceptor.ErrorWebFilter
import top.lolosia.web.manager.SessionManager
import top.lolosia.web.model.system.SysUserEntity
import top.lolosia.web.model.system.SysUserRolesEntity
import top.lolosia.web.model.system.SystemModel
import top.lolosia.web.model.system.query.QSysRoleEntity
import top.lolosia.web.model.system.query.QSysUserEntity
import top.lolosia.web.model.system.query.QSysUserRolesEntity
import top.lolosia.web.model.system.query.QViewUserRoleEntity
import top.lolosia.web.util.bundle.*
import top.lolosia.web.util.ebean.*
import top.lolosia.web.util.session.Context
import top.lolosia.web.util.success
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.io.FileSystemResource
import org.springframework.http.codec.multipart.FilePart
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import java.io.IOException
import java.util.*
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

@Service
class UserService {
    init {
        Path("./work/image/avatar").createDirectories()
    }

    @Autowired
    lateinit var encoder: PasswordEncoder

    @Autowired
    lateinit var mapper: JsonMapper

    @Autowired
    lateinit var session: SessionManager

    @Autowired
    lateinit var userRoleService: UserRoleService

    @Suppress("UNCHECKED_CAST")
    fun list(ctx: Context, pageNo: Int = 1, pageSize: Int = 10, keys: String? = null): Pair<List<Bundle>, Int> {
        val arrList = bundleOf()
        if (!keys.isNullOrEmpty()) {
            // 查询User匹配信息
            QSysUserEntity().run {
                or {
                    userName.contains(keys)
                    realName.contains(keys)
                    phone.contains(keys)
                }
                val idList = findList().map { it.id }.toSet()
                if (idList.isNotEmpty()) arrList["userId"] = idList
            }
            // 查询角色匹配信息
            QSysRoleEntity().run {
                roleName.contains(keys)
                val rs = findList().map { it.id }.toSet()
                if (rs.isNotEmpty()) arrList["roleId"] = rs
            }
        }

        val offset = (pageNo - 1) * pageSize

        val (userRoles, count) = QViewUserRoleEntity().run {
            roleId.ne(1)
            if (arrList.isNotEmpty()) {
                if (arrList.size > 1) or()
                arrList.forEach { (k, v) ->
                    when (k) {
                        "userId" -> userId.isIn(v as Set<UUID>)
                        "roleId" -> roleId.isIn(v as Set<Int>)
                    }
                }
                if (arrList.size > 1) endOr()
            }
            orderBy {
                roleId.asc()
                userId.asc()
            }
            setFirstRow(offset)
            setMaxRows(pageSize)
            findPagedList().run {
                list.map(mapper::toBundle) to totalCount
            }
        }

        if (userRoles.isNotEmpty()) {
            val users = QSysUserEntity().run {
                id.isIn(userRoles.map { it.getAs<String>("userId")!!.toUuid() })
                id.asMapKey()
                findMap<UUID>().mapValues { (_, v) -> mapper.toBundle(v) }
            }
            userRoles.forEach {
                it.scope {
                    "realName" set ""
                    "roleType" set it("type")
                    users[it["userId"].toString().toUuid()]?.let { user ->
                        user.remove("password")
                        it.putAll(user)
                    } ?: let {
                        "realName" set "<账户不存在>"
                        "isUse" set false
                    }
                }
            }
        }

        return userRoles to count
    }

    /**
     * 按照文本搜索用户，默认只显示10行
     * @param keys 关键字
     */
    fun userSearching(ctx: Context, keys: String?): List<SysUserEntity> {
        QSysUserEntity().run {
            if (!keys.isNullOrEmpty()) or {
                userName.contains(keys)
                realName.contains(keys)
                phone.contains(keys)
            }
            setMaxRows(10)
            return findList()
        }
    }

    /**
     * 查找用户
     * @param idList ID列表。根据经验，元素可能为 null。
     */
    fun get(ctx: Context, idList: List<String?>): List<Bundle> {
        QSysUserEntity().run {
            id.isIn(idList.filterNotNull().map { it.toUuid() })
            return mapper.toBundle(findList()).onEach {
                it.remove("password")
            }
        }
    }

    fun create(ctx: Context, data: Bundle): Any {
        if ("userName" !in data) data["userName"] = data["phone"]
        val phone: String = data("phone")!!
        val roleId: Int = data("roleId")!!
        val userName: String = data("userName")!!

        // 查询或创建用户
        val exists = QSysUserEntity().run {
            or {
                this.phone.eq(phone)
                this.userName.eq(userName)
            }
            exists()
        }

        if (exists) {
            throw IllegalStateException("同名用户已存在")
        }

        SystemModel.transaction { tran ->
            val userDetail = mapper.toModel<SysUserEntity>(data).apply {
                id = UUID.randomUUID()
                insert(tran)
            }

            QSysUserRolesEntity().run {
                userId.eq(userDetail.id)
                findOne()
            } ?: SysUserRolesEntity().apply {
                this.userId = userDetail.id
                this.roleId = roleId
                this.createdBy = ctx.user.id
                insert(tran)
                return this
            }
            return success()
        }
    }

    fun getUserInfo(ctx: Context, id: String): Bundle {
        val id1 = id.toUuid()
        val userInfo = QSysUserEntity().run {
            this.id.eq(id1)
            mapper.toBundle(findOne() ?: throw NoSuchElementException("找不到用户"))
        }

        val roleId = QSysUserRolesEntity().run {
            userId.eq(id1)
            findOne()
        }
        if (roleId != null) {
            userInfo["roleId"] = roleId.roleId
        } else {
            SysUserRolesEntity().apply {
                this.userId = id1
                this.roleId = 7
                this.createdBy = ctx.user.id
            }
            userInfo["roleId"] = 7
        }
        return userInfo
    }

    /** 修改用户 */
    fun update(ctx: Context, data: Bundle): Any {
        // 加密或移除密码
        data["password"]?.let {
            val str = it.toString()
            if (str.isNotEmpty()) {
                data["password"] = encoder.encode(str)
            } else data.remove("password")
        }
        SystemModel.transaction { tran ->
            // 更新用户信息
            mapper.toModel<SysUserEntity>(data).update(tran)
            // 更新角色信息
            data.getAs<Int>("roleId")?.let { roleId ->
                val id: UUID = data["id"]!!.toString().toUuid()
                val role = QSysUserRolesEntity().userId.eq(id).findOne()
                if (role != null && role.roleId != roleId) {
                    role.roleId = roleId
                    role.save(tran)
                } else if (role == null) {
                    SysUserRolesEntity().apply {
                        this.roleId = roleId
                        this.userId = id
                        save(tran)
                    }
                }
            }
        }
        return success()
    }

    fun delete(ctx: Context, ids: List<String>): Any {
        SystemModel.transaction { tran ->
            QSysUserRolesEntity().apply {
                this.userId.isIn(ids.map { it.toUuid() })
                delete(tran)
            }
        }
        return success()
    }

    fun updatePassword(ctx: Context, id: String, origin: String, target: String): Any {
        val user = QSysUserEntity().id.eq(id.toUuid()).findOne() ?: throw NoSuchElementException("用户不存在")
        if (!user.password.isNullOrEmpty() && !encoder.matches(origin, user.password)) {
            throw IllegalStateException("密码错误！")
        }
        user.password = target
        user.update()
        return success()
    }

    fun myUserInfo(ctx: Context): Bundle {
        val obj = ctx.session
        val user = QSysUserEntity().run {
            id.eq(ctx.user.id)
            deleted.ne(true)
            findOne()!!
        }
        return bundleOf().also {
            it.putAll(obj)
            it.putAll(mapper.toBundle(user))
            it.remove("password")
        }
    }

    fun myUserRole(ctx: Context): Bundle {
        return userRoleService.getRoleByUserId(ctx, bundleScope {
            "userId" set ctx.userId
        })
    }

    fun avatar(ctx: Context, id: String): FileSystemResource {
        val avatar = QSysUserEntity().run {
            this.id.eq(id.toUuid())
            findOne()?.avatar
        }
        if (avatar != null && Path("work/image/avatar", avatar).exists()) {
            return FileSystemResource(Path("work/image/avatar", avatar).absolutePathString())
        }
        throw object : IOException("文件 \"$avatar\" 不存在"), ErrorWebFilter.Ignore {
            override fun fillInStackTrace(): Throwable {
                return this
            }
        }
    }

    suspend fun avatar(ctx: Context, id: String, file: FilePart): Any {
        val ext = file.filename().split(".").last()
        val filename = "${id}.${ext}"
        val outFile = Path("work/image/avatar", filename).toFile()
        if (outFile.exists()) outFile.delete()
        file.transferTo(outFile).awaitSingleOrNull()
        return success()
    }
}