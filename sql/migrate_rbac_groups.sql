/*
 RBAC 组模型升级（对**已有库**执行一次；新库直接用 sys_role.sql / sys_role_permission.sql）。

 1. sys_role 加 parent_id（组继承）
 2. 角色改名要「组」：super_admin → owner、student → user，展示名一并更新
 3. 删除历史遗留的 teacher 组（连同其绑定与规则）
 4. 落继承关系：admin → user → guest
 5. 清掉内置组的旧种子规则 —— 默认规则改由内嵌资源 permissions.yaml 提供
    （判定时「DB 有就用 DB，没有才用注册表」，清空即让注册表生效）

 幂等性：除 ALTER 外均可重复执行；ALTER 在已加过列时会报 1060，可忽略。
*/

SET NAMES utf8mb4;

-- 1) 组继承列
ALTER TABLE `sys_role` ADD COLUMN `parent_id` int(11) NULL DEFAULT NULL COMMENT '父组 id（组继承），NULL 为根基组' AFTER `type`;

-- 2) 改名
UPDATE `sys_role` SET `type` = 'owner', `role_name` = '拥有者',   `updated_at` = NOW() WHERE `type` = 'super_admin';
UPDATE `sys_role` SET `type` = 'user',  `role_name` = '普通用户', `updated_at` = NOW() WHERE `type` = 'student';

-- 3) 删除 teacher 组（先清绑定与规则，再删组本身）
DELETE FROM `sys_user_roles`      WHERE `role_id` IN (SELECT `id` FROM `sys_role` WHERE `type` = 'teacher');
DELETE FROM `sys_role_permission` WHERE `role_id` IN (SELECT `id` FROM `sys_role` WHERE `type` = 'teacher');
DELETE FROM `sys_role`            WHERE `type` = 'teacher';

-- 4) 继承关系：admin → user → guest（owner / guest 为根基组）
UPDATE `sys_role` AS r JOIN `sys_role` AS u ON u.`type` = 'user'  SET r.`parent_id` = u.`id` WHERE r.`type` = 'admin';
UPDATE `sys_role` AS r JOIN `sys_role` AS g ON g.`type` = 'guest' SET r.`parent_id` = g.`id` WHERE r.`type` = 'user';
UPDATE `sys_role` SET `parent_id` = NULL WHERE `type` IN ('owner', 'guest');

-- 5) 清掉内置组的旧种子规则，让 permissions.yaml 的默认值生效
DELETE FROM `sys_role_permission`
WHERE `role_id` IN (SELECT `id` FROM `sys_role` WHERE `type` IN ('owner', 'admin', 'user', 'guest'));
