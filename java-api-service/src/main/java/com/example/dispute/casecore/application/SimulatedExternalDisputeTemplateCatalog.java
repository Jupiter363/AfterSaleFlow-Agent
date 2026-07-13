/*
 * 所属模块：案件核心与导入。
 * 文件职责：维护模拟外部争议模板白名单并按稳定键解析实现。
 * 业务链路：核心入口/契约为 「all」、「size」、「get」；维护争议案件事实、来源、幂等导入和演示数据清理。
 * 关键边界：Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
 */
package com.example.dispute.casecore.application;

import static com.example.dispute.domain.model.RiskLevel.HIGH;
import static com.example.dispute.domain.model.RiskLevel.LOW;
import static com.example.dispute.domain.model.RiskLevel.MEDIUM;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Component;

/** Deterministic UTF-8 demo cases used by the local external-order importer. */
// 所属模块：【案件核心与导入 / 应用编排层】类型「SimulatedExternalDisputeTemplateCatalog」。
// 类型职责：维护模拟外部争议模板白名单并按稳定键解析实现；本类型显式提供 「all」、「size」、「get」、「template」。
// 协作关系：主要由 「ExternalCaseImportTransactionService.simulateExternalImport」、「SimulatedExternalDisputeTemplateCatalogTest.containsExactlyTwentyOrderedUniqueAndCompleteUtf8Templates」、「SimulatedExternalImportTemplateCycleTest.importsTemplatesOneThroughTwentyThenCyclesBackToOne」、「SimulatedExternalImportTemplateCycleTest.mapsMerchantInitiatedTemplateClaimAsFormFactsWithoutInventedStatementsOrAttitude」 使用。
// 边界意义：Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Component
public class SimulatedExternalDisputeTemplateCatalog {

    public static final String SOURCE_SYSTEM = "TEMPLATE_SIMULATED_OMS";

    private static final List<SimulatedExternalDisputeTemplate> TEMPLATES =
            List.of(
                    template(1, "物流显示签收但本人未收到", "物流轨迹显示订单已签收，发起方表示本人及同住人员均未收到包裹，要求核验签收人、投递位置和签收凭证。", "物流显示已签收，但我和同住人员都没有收到包裹，请核验签收记录并为我退款。商家说物流已经完成签收，暂时不支持直接退款。", "SIGNED_NOT_RECEIVED", HIGH, "REFUND", "299.00", "蓝牙耳机 1 件", "未实际收到商品，希望在核验物流交接链路后退款。", "DISAGREE", "对方认为物流已完成签收，暂不支持直接退款。"),
                    template(2, "到货商品破损影响使用", "商品外包装存在明显挤压，拆箱后发现机身开裂且无法正常开机，双方对运输破损责任存在分歧。", "我拆箱后发现平板机身开裂且无法开机，希望退货退款，并查清运输破损责任。商家要求我补充完整开箱视频和外包装照片后再判断责任。", "DELIVERY_DAMAGE", HIGH, "RETURN_REFUND", "1299.00", "平板电脑 1 台", "商品到货即损坏且无法使用，要求退货退款并核验运输责任。", "NEED_MORE_INFO", "对方要求补充完整开箱视频和外包装照片后再判断责任。"),
                    template(3, "套装配件缺失要求补发", "订单为完整套装，实际包裹中缺少标配充电器和连接线，主商品可以正常使用。", "我买的是完整套装，收到后发现没有标配充电器和连接线，手表能用，但请把缺少的配件补发给我。商家说要先核对出库称重，目前还没有确认缺件数量。", "MISSING_ACCESSORIES", MEDIUM, "RESHIP", null, "智能手表充电器及连接线各 1 件", "实收内容与商品清单不一致，要求补发缺失配件。", "PARTIALLY_AGREE", "对方认可需要核对出库称重，但尚未确认缺件数量。"),
                    template(4, "收到商品与下单型号不符", "订单购买黑色大容量型号，实际收到白色基础型号，商品尚未激活使用。", "我下单的是黑色 256GB 手机，收到的却是白色基础型号，商品还没激活，请给我换成正确型号。商家初步承认仓库可能发错，同意核验序列号后换货。", "WRONG_ITEM", MEDIUM, "REPLACE_OR_REPAIR", null, "黑色 256GB 手机 1 台", "发错型号且商品未使用，要求换成订单约定型号。", "AGREE", "对方初步认可仓库可能错发，同意核验序列号后换货。"),
                    template(5, "使用短期后出现质量故障", "商品正常使用十天后频繁自动关机，远程排障和恢复出厂设置均未解决。", "我的笔记本正常用了十天就频繁自动关机，排障和恢复出厂都没用，希望换货或安排有效维修。商家让我先寄回检测，确认不是人为损坏后才维修或换货。", "PRODUCT_QUALITY", HIGH, "REPLACE_OR_REPAIR", null, "轻薄笔记本电脑 1 台", "故障发生在质保期内，要求换货或提供有效维修方案。", "ALTERNATIVE_PROPOSED", "对方建议先寄回检测，确认非人为损坏后再维修或换货。"),
                    template(6, "承诺时效内未送达", "订单页面承诺次日送达，实际延迟五天且影响预定使用安排，商品最终已签收。", "页面承诺次日送达，但我的礼物晚了五天才到，已经耽误使用，希望补偿 80 元。商家承认配送延误，但不同意我的补偿金额。", "DELIVERY_DELAY", MEDIUM, "COMPENSATION", "80.00", "生日礼品礼盒 1 套", "配送严重超过承诺时效，要求补偿由延误造成的损失。", "PARTIALLY_AGREE", "对方认可配送延误，但对补偿金额存在异议。"),
                    template(7, "退货入库后退款迟迟未到账", "退货物流显示仓库已签收七天，售后页面仍停留在待收货状态，退款未发起。", "我的退货七天前就被仓库签收了，售后仍显示待收货，459 元退款一直没到账，请尽快处理。客服说仓库还没完成质检，需要核对退件批次。", "REFUND_DELAY", MEDIUM, "REFUND", "459.00", "运动鞋 1 双", "退货已送达商家仓库，要求尽快完成退款。", "NEED_MORE_INFO", "对方称仓库尚未完成质检，需要核对退件批次。"),
                    template(8, "优惠承诺与实际结算不一致", "直播间宣称下单后返还差价，订单完成后客服表示活动名额已满，双方对活动规则理解不同。", "我是看到直播间承诺返差价才下单的，现在客服说名额已满，我要求兑现 120 元优惠。商家说我的订单未满足活动细则中的指定支付条件。", "PROMOTION_DISPUTE", MEDIUM, "COMPENSATION", "120.00", "家用咖啡机 1 台", "购买决策基于返差价承诺，要求兑现活动优惠。", "DISAGREE", "对方认为订单未满足活动细则中的指定支付条件。"),
                    template(9, "自动续费扣款存在争议", "订阅服务在到期日自动扣款，发起方称未看到显著续费提示且扣款后未使用新周期服务。", "我没有注意到明确的自动续费提示，扣款后也没使用新周期服务，希望退还 198 元年费。客服说开通页面已经展示自动续费规则，暂时不支持退款。", "AUTO_RENEWAL", HIGH, "REFUND", "198.00", "会员年费 1 年", "未明确知悉自动续费且新周期未使用，要求退还续费金额。", "DISAGREE", "对方认为开通页面已展示自动续费规则，暂不支持退款。"),
                    template(10, "生鲜到货变质无法食用", "冷链包裹到达时冰袋已完全融化，部分商品出现异味，收货后立即拍照并联系客服。", "我收到海鲜时冰袋全化了，商品还有异味，已经第一时间拍照，请全额退款。客服要求我提供签收时间、温度记录和全部商品照片。", "FRESH_FOOD_DAMAGE", HIGH, "REFUND", "168.00", "冷冻海鲜礼盒 1 箱", "冷链失效导致商品无法食用，要求全额退款。", "NEED_MORE_INFO", "对方要求核验签收时间、温度记录和全部商品照片。"),
                    template(11, "定制商品内容制作错误", "定制礼品收到后发现刻字内容与订单确认稿不一致，商品无法用于原定场合。", "我收到的定制相册刻字和确认稿不一样，已经没法用于原定场合，请按正确内容重新制作并补发。商家承认成品有差异，但说还要核对最终确认版本。", "CUSTOMIZATION_ERROR", MEDIUM, "RESHIP", null, "定制纪念相册 1 本", "制作内容与确认稿不符，要求按正确内容重新制作并补发。", "PARTIALLY_AGREE", "对方认可成品存在差异，但需要核对最终确认版本。"),
                    template(12, "安装服务额外收费争议", "商品页面标注包含基础安装，上门人员以现场条件复杂为由收取额外费用，收费项未提前说明。", "页面写着包含基础安装，上门后却临时收了我 150 元且事先没说明，请退还这笔费用。商家说现场产生了超出基础安装范围的材料和施工费用。", "SERVICE_FEE_DISPUTE", MEDIUM, "REFUND", "150.00", "壁挂电视安装服务 1 次", "额外费用缺乏事前告知，要求退还不合理收费。", "DISAGREE", "对方认为现场产生了超出基础安装范围的材料和施工费用。"),
                    template(13, "维修后同一故障再次出现", "商品完成一次保修维修后不到两周再次出现相同故障，发起方对继续维修失去信心。", "我的扫地机器人维修后不到两周又出现同样故障，我不想再反复维修，要求直接换机。商家只同意再次检测维修，检测前不同意直接换机。", "REPEATED_REPAIR", HIGH, "REPLACE_OR_REPAIR", null, "扫地机器人 1 台", "同一故障反复发生，要求更换整机而非再次维修。", "ALTERNATIVE_PROPOSED", "对方提出再次检测维修，并拒绝在检测前直接换机。"),
                    template(14, "二手商品成色描述不符", "页面标注九五新且无明显划痕，收货后发现屏幕和边框有多处明显磨损。", "页面标注九五新且无明显划痕，但我收到的相机屏幕和边框磨损明显，我要退货退款。商家说这些磨损属于二手商品正常使用痕迹。", "CONDITION_MISMATCH", MEDIUM, "RETURN_REFUND", "2399.00", "二手相机 1 台", "实际成色显著低于页面描述，要求退货退款。", "DISAGREE", "对方认为现有磨损属于二手商品正常使用痕迹。"),
                    template(15, "赠品未随主商品发放", "订单活动页显示购买主商品赠送指定赠品，主商品已签收但包裹和订单记录中均无赠品。", "我下单时活动明确赠送保温杯，但收到主商品后没有赠品，请按承诺补发。客服说还要核验下单时间是否处于赠品库存有效期。", "MISSING_GIFT", LOW, "RESHIP", null, "活动赠品保温杯 1 个", "订单满足活动条件，要求补发承诺赠品。", "NEED_MORE_INFO", "对方表示需要核验下单时间是否处于赠品库存有效期。"),
                    template(16, "无理由退货被认定影响二次销售", "商品仅拆封查看尺寸后申请退货，对方以包装已拆为由拒绝，双方对是否影响二次销售存在分歧。", "我只是拆封看了尺寸，没有安装使用，也还在退货期内，请同意退货退款。商家说密封包装已经破坏，不符合无理由退货条件。", "RETURN_ELIGIBILITY", MEDIUM, "RETURN_REFUND", "699.00", "人体工学椅配件 1 套", "商品未安装使用且仍在退货期限内，要求退货退款。", "DISAGREE", "对方认为密封包装已破坏，商品不符合无理由退货条件。"),
                    template(17, "虚拟权益未到账", "兑换码购买成功且订单已完成，但账户中未到账对应权益，重新登录和绑定均无效。", "我购买的会员兑换码显示订单完成，但账户权益一直没到账，重新登录也没用，请重新发放。客服说需要核对兑换账户、兑换码状态和绑定记录。", "DIGITAL_DELIVERY_FAILURE", MEDIUM, "RESHIP", null, "视频平台季度会员 1 份", "已支付但未获得数字权益，要求重新发放有效权益。", "NEED_MORE_INFO", "对方需要核对兑换账户、兑换码状态和绑定记录。"),
                    template(18, "保价商品运输丢失赔付不足", "高价值包裹购买了保价服务，物流确认运输丢失，但提出的赔付金额低于申报价值。", "我的保价包裹已经确认丢失，但赔付低于申报价值，我要求按 3200 元申报价值赔偿。商家承认丢件责任，但对商品价值证明和赔付上限有异议。", "INSURED_LOGISTICS_LOSS", HIGH, "COMPENSATION", "3200.00", "收藏模型 1 件", "包裹已确认丢失且购买保价，要求按申报价值赔付。", "PARTIALLY_AGREE", "对方认可丢件责任，但对商品价值证明和赔付上限有异议。"),
                    template(19, "订单取消后仍然发货", "发起方在订单出库前提交取消并收到受理提示，随后系统仍发货并产生退回运费争议。", "我在出库前提交取消并收到受理提示，订单后来还是发货了，请关闭订单并免除退回运费。商家说我提交取消时仓库已经完成出库。", "CANCELLATION_FAILURE", MEDIUM, "CANCEL_ORDER", "35.00", "家居收纳箱 2 个", "取消申请已被受理，要求关闭订单并免除退回运费。", "DISAGREE", "对方认为取消申请提交时仓库已经完成出库。"),
                    template(20, "商品参数宣传与检测结果不符", "页面宣称达到指定性能参数，第三方检测和实际使用结果均明显低于宣传值，双方对检测方法存在争议。", "我购买的净化器实测性能明显低于页面宣传参数，希望退货退款，并核验宣传参数依据。商家不认可现有检测条件，认为结果不能代表标准工况。", "SPECIFICATION_MISMATCH", HIGH, "RETURN_REFUND", "1899.00", "空气净化器 1 台", "核心性能未达到宣传标准，要求退货退款并核验参数依据。", "DISAGREE", "对方不认可现有检测条件，认为结果不能代表标准工况。"));

    // 所属模块：【案件核心与导入 / 应用编排层】「SimulatedExternalDisputeTemplateCatalog.all()」。
    // 具体功能：「SimulatedExternalDisputeTemplateCatalog.all()」：列出列表，最终返回「List<SimulatedExternalDisputeTemplate>」。
    // 上游调用：「SimulatedExternalDisputeTemplateCatalog.all()」的上游调用点包括 「SimulatedExternalDisputeTemplateCatalogTest.containsExactlyTwentyOrderedUniqueAndCompleteUtf8Templates」、「SimulatedExternalImportTemplateCycleTest.importsTemplatesOneThroughTwentyThenCyclesBackToOne」。
    // 下游影响：「SimulatedExternalDisputeTemplateCatalog.all()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「List<SimulatedExternalDisputeTemplate>」交给调用方。
    // 系统意义：「SimulatedExternalDisputeTemplateCatalog.all()」负责主链路中的“列表”；Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
    public List<SimulatedExternalDisputeTemplate> all() {
        return TEMPLATES;
    }

    // 所属模块：【案件核心与导入 / 应用编排层】「SimulatedExternalDisputeTemplateCatalog.size()」。
    // 具体功能：「SimulatedExternalDisputeTemplateCatalog.size()」：返回启动期加载的模拟争议模板总数，供轮询游标取模和测试验证模板循环边界，最终返回「int」。
    // 上游调用：「SimulatedExternalDisputeTemplateCatalog.size()」的上游调用点包括 「ExternalCaseImportTransactionService.simulateExternalImport」。
    // 下游影响：「SimulatedExternalDisputeTemplateCatalog.size()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「int」交给调用方。
    // 系统意义：「SimulatedExternalDisputeTemplateCatalog.size()」负责主链路中的“size”；Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
    public int size() {
        return TEMPLATES.size();
    }

    // 所属模块：【案件核心与导入 / 应用编排层】「SimulatedExternalDisputeTemplateCatalog.get(int)」。
    // 具体功能：「SimulatedExternalDisputeTemplateCatalog.get(int)」：读取「SimulatedExternalDisputeTemplateCatalog」中的「」状态，向 JPA、应用服务或序列化层返回「SimulatedExternalDisputeTemplate」。
    // 上游调用：「SimulatedExternalDisputeTemplateCatalog.get(int)」的上游调用点包括 「ExternalCaseImportTransactionService.simulateExternalImport」、「SimulatedExternalImportTemplateCycleTest.importsTemplatesOneThroughTwentyThenCyclesBackToOne」、「SimulatedExternalImportTemplateCycleTest.mapsUserInitiatedTemplateClaimAsFormFactsWithoutInventedStatementsOrAttitude」、「SimulatedExternalImportTemplateCycleTest.mapsMerchantInitiatedTemplateClaimAsFormFactsWithoutInventedStatementsOrAttitude」。
    // 下游影响：「SimulatedExternalDisputeTemplateCatalog.get(int)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「SimulatedExternalDisputeTemplate」交给调用方。
    // 系统意义：「SimulatedExternalDisputeTemplateCatalog.get(int)」负责主链路中的“模拟外部争议模板”；Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
    public SimulatedExternalDisputeTemplate get(int templateNo) {
        if (templateNo < 1 || templateNo > TEMPLATES.size()) {
            throw new IllegalArgumentException("templateNo must be between 1 and " + TEMPLATES.size());
        }
        return TEMPLATES.get(templateNo - 1);
    }

    // 所属模块：【案件核心与导入 / 应用编排层】「SimulatedExternalDisputeTemplateCatalog.template(int,String,String,String,String,RiskLevel,String,String,String,String,String,String)」。
    // 具体功能：「SimulatedExternalDisputeTemplateCatalog.template(int,String,String,String,String,RiskLevel,String,String,String,String,String,String)」：构建模板，最终返回「SimulatedExternalDisputeTemplate」。
    // 上游调用：「SimulatedExternalDisputeTemplateCatalog.template(int,String,String,String,String,RiskLevel,String,String,String,String,String,String)」只由「SimulatedExternalDisputeTemplateCatalog」内部流程使用，负责封装“模板”这一步校验、映射或状态转换。
    // 下游影响：「SimulatedExternalDisputeTemplateCatalog.template(int,String,String,String,String,RiskLevel,String,String,String,String,String,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「SimulatedExternalDisputeTemplate」交给调用方。
    // 系统意义：「SimulatedExternalDisputeTemplateCatalog.template(int,String,String,String,String,RiskLevel,String,String,String,String,String,String)」负责主链路中的“模板”；Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
    private static SimulatedExternalDisputeTemplate template(
            int number,
            String title,
            String description,
            String originalStatement,
            String type,
            com.example.dispute.domain.model.RiskLevel risk,
            String resolution,
            String amount,
            String items,
            String reason,
            String attitude,
            String position) {
        return new SimulatedExternalDisputeTemplate(
                number,
                title,
                description,
                originalStatement,
                type,
                risk,
                resolution,
                amount == null ? null : new BigDecimal(amount),
                items,
                reason,
                attitude,
                position);
    }
}
