package com.djzy.assistant.management.repo;

/**
 * 一行角色（{@code sys_role}）的只读视图，给管理端填「角色」下拉框用。
 *
 * <p>为什么专门开一个只读出口：角色是权限配置的**主语**（{@code role_api} / {@code role_skill} 都挂在它上面），
 * 而在它出现之前，管理端只能靠人肉敲角色编码——敲错一个字母的表现不是报错，而是"这个角色一条授权都没有"，
 * 看着像没配过，实际是查错了人。角色本身仍由账号体系维护，管理端**只读不写**，所以这里没有写端点。
 *
 * @param roleCode 角色编码（{@code sys_role.role_code}，授权表里存的就是它）
 * @param roleName 展示名（可改，别拿它当身份）
 * @param remark 备注（这个角色大概管什么，纯说明）
 */
public record RoleView(String roleCode, String roleName, String remark) {}
