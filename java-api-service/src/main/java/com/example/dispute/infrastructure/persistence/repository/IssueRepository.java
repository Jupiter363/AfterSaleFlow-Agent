/*
 * 所属模块：PostgreSQL 事实模型。
 * 文件职责：声明争点在 PostgreSQL 中的查询与写入契约。
 * 业务链路：该文件主要提供类型或包级契约；映射案件全链路实体并提供 Spring Data 仓储查询。
 * 关键边界：实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
 */
package com.example.dispute.infrastructure.persistence.repository;

import com.example.dispute.infrastructure.persistence.entity.IssueEntity;
import org.springframework.data.jpa.repository.JpaRepository;

// 所属模块：【PostgreSQL 事实模型 / 仓储接口层】类型「IssueRepository」。
// 类型职责：声明争点在 PostgreSQL 中的查询与写入契约；本类型显式提供 框架生成的默认访问器。
// 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
// 边界意义：实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
// Java 语法：interface 只定义能力契约，调用方依赖接口而不是具体适配器。
public interface IssueRepository extends JpaRepository<IssueEntity, String> {}
