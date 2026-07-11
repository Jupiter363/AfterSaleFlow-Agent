package com.example.dispute.casecore.application;

import static com.example.dispute.domain.model.RiskLevel.HIGH;
import static com.example.dispute.domain.model.RiskLevel.LOW;
import static com.example.dispute.domain.model.RiskLevel.MEDIUM;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Component;

/** Deterministic UTF-8 demo cases used by the local external-order importer. */
@Component
public class SimulatedExternalDisputeTemplateCatalog {

    public static final String SOURCE_SYSTEM = "TEMPLATE_SIMULATED_OMS";

    private static final List<SimulatedExternalDisputeTemplate> TEMPLATES =
            List.of(
                    template(1, "物流显示签收但本人未收到", "物流轨迹显示订单已签收，发起方表示本人及同住人员均未收到包裹，要求核验签收人、投递位置和签收凭证。", "SIGNED_NOT_RECEIVED", HIGH, "REFUND", "299.00", "蓝牙耳机 1 件", "未实际收到商品，希望在核验物流交接链路后退款。", "DISAGREE", "对方认为物流已完成签收，暂不支持直接退款。"),
                    template(2, "到货商品破损影响使用", "商品外包装存在明显挤压，拆箱后发现机身开裂且无法正常开机，双方对运输破损责任存在分歧。", "DELIVERY_DAMAGE", HIGH, "RETURN_REFUND", "1299.00", "平板电脑 1 台", "商品到货即损坏且无法使用，要求退货退款并核验运输责任。", "NEED_MORE_INFO", "对方要求补充完整开箱视频和外包装照片后再判断责任。"),
                    template(3, "套装配件缺失要求补发", "订单为完整套装，实际包裹中缺少标配充电器和连接线，主商品可以正常使用。", "MISSING_ACCESSORIES", MEDIUM, "RESHIP", null, "智能手表充电器及连接线各 1 件", "实收内容与商品清单不一致，要求补发缺失配件。", "PARTIALLY_AGREE", "对方认可需要核对出库称重，但尚未确认缺件数量。"),
                    template(4, "收到商品与下单型号不符", "订单购买黑色大容量型号，实际收到白色基础型号，商品尚未激活使用。", "WRONG_ITEM", MEDIUM, "REPLACE_OR_REPAIR", null, "黑色 256GB 手机 1 台", "发错型号且商品未使用，要求换成订单约定型号。", "AGREE", "对方初步认可仓库可能错发，同意核验序列号后换货。"),
                    template(5, "使用短期后出现质量故障", "商品正常使用十天后频繁自动关机，远程排障和恢复出厂设置均未解决。", "PRODUCT_QUALITY", HIGH, "REPLACE_OR_REPAIR", null, "轻薄笔记本电脑 1 台", "故障发生在质保期内，要求换货或提供有效维修方案。", "ALTERNATIVE_PROPOSED", "对方建议先寄回检测，确认非人为损坏后再维修或换货。"),
                    template(6, "承诺时效内未送达", "订单页面承诺次日送达，实际延迟五天且影响预定使用安排，商品最终已签收。", "DELIVERY_DELAY", MEDIUM, "COMPENSATION", "80.00", "生日礼品礼盒 1 套", "配送严重超过承诺时效，要求补偿由延误造成的损失。", "PARTIALLY_AGREE", "对方认可配送延误，但对补偿金额存在异议。"),
                    template(7, "退货入库后退款迟迟未到账", "退货物流显示仓库已签收七天，售后页面仍停留在待收货状态，退款未发起。", "REFUND_DELAY", MEDIUM, "REFUND", "459.00", "运动鞋 1 双", "退货已送达商家仓库，要求尽快完成退款。", "NEED_MORE_INFO", "对方称仓库尚未完成质检，需要核对退件批次。"),
                    template(8, "优惠承诺与实际结算不一致", "直播间宣称下单后返还差价，订单完成后客服表示活动名额已满，双方对活动规则理解不同。", "PROMOTION_DISPUTE", MEDIUM, "COMPENSATION", "120.00", "家用咖啡机 1 台", "购买决策基于返差价承诺，要求兑现活动优惠。", "DISAGREE", "对方认为订单未满足活动细则中的指定支付条件。"),
                    template(9, "自动续费扣款存在争议", "订阅服务在到期日自动扣款，发起方称未看到显著续费提示且扣款后未使用新周期服务。", "AUTO_RENEWAL", HIGH, "REFUND", "198.00", "会员年费 1 年", "未明确知悉自动续费且新周期未使用，要求退还续费金额。", "DISAGREE", "对方认为开通页面已展示自动续费规则，暂不支持退款。"),
                    template(10, "生鲜到货变质无法食用", "冷链包裹到达时冰袋已完全融化，部分商品出现异味，收货后立即拍照并联系客服。", "FRESH_FOOD_DAMAGE", HIGH, "REFUND", "168.00", "冷冻海鲜礼盒 1 箱", "冷链失效导致商品无法食用，要求全额退款。", "NEED_MORE_INFO", "对方要求核验签收时间、温度记录和全部商品照片。"),
                    template(11, "定制商品内容制作错误", "定制礼品收到后发现刻字内容与订单确认稿不一致，商品无法用于原定场合。", "CUSTOMIZATION_ERROR", MEDIUM, "RESHIP", null, "定制纪念相册 1 本", "制作内容与确认稿不符，要求按正确内容重新制作并补发。", "PARTIALLY_AGREE", "对方认可成品存在差异，但需要核对最终确认版本。"),
                    template(12, "安装服务额外收费争议", "商品页面标注包含基础安装，上门人员以现场条件复杂为由收取额外费用，收费项未提前说明。", "SERVICE_FEE_DISPUTE", MEDIUM, "REFUND", "150.00", "壁挂电视安装服务 1 次", "额外费用缺乏事前告知，要求退还不合理收费。", "DISAGREE", "对方认为现场产生了超出基础安装范围的材料和施工费用。"),
                    template(13, "维修后同一故障再次出现", "商品完成一次保修维修后不到两周再次出现相同故障，发起方对继续维修失去信心。", "REPEATED_REPAIR", HIGH, "REPLACE_OR_REPAIR", null, "扫地机器人 1 台", "同一故障反复发生，要求更换整机而非再次维修。", "ALTERNATIVE_PROPOSED", "对方提出再次检测维修，并拒绝在检测前直接换机。"),
                    template(14, "二手商品成色描述不符", "页面标注九五新且无明显划痕，收货后发现屏幕和边框有多处明显磨损。", "CONDITION_MISMATCH", MEDIUM, "RETURN_REFUND", "2399.00", "二手相机 1 台", "实际成色显著低于页面描述，要求退货退款。", "DISAGREE", "对方认为现有磨损属于二手商品正常使用痕迹。"),
                    template(15, "赠品未随主商品发放", "订单活动页显示购买主商品赠送指定赠品，主商品已签收但包裹和订单记录中均无赠品。", "MISSING_GIFT", LOW, "RESHIP", null, "活动赠品保温杯 1 个", "订单满足活动条件，要求补发承诺赠品。", "NEED_MORE_INFO", "对方表示需要核验下单时间是否处于赠品库存有效期。"),
                    template(16, "无理由退货被认定影响二次销售", "商品仅拆封查看尺寸后申请退货，对方以包装已拆为由拒绝，双方对是否影响二次销售存在分歧。", "RETURN_ELIGIBILITY", MEDIUM, "RETURN_REFUND", "699.00", "人体工学椅配件 1 套", "商品未安装使用且仍在退货期限内，要求退货退款。", "DISAGREE", "对方认为密封包装已破坏，商品不符合无理由退货条件。"),
                    template(17, "虚拟权益未到账", "兑换码购买成功且订单已完成，但账户中未到账对应权益，重新登录和绑定均无效。", "DIGITAL_DELIVERY_FAILURE", MEDIUM, "RESHIP", null, "视频平台季度会员 1 份", "已支付但未获得数字权益，要求重新发放有效权益。", "NEED_MORE_INFO", "对方需要核对兑换账户、兑换码状态和绑定记录。"),
                    template(18, "保价商品运输丢失赔付不足", "高价值包裹购买了保价服务，物流确认运输丢失，但提出的赔付金额低于申报价值。", "INSURED_LOGISTICS_LOSS", HIGH, "COMPENSATION", "3200.00", "收藏模型 1 件", "包裹已确认丢失且购买保价，要求按申报价值赔付。", "PARTIALLY_AGREE", "对方认可丢件责任，但对商品价值证明和赔付上限有异议。"),
                    template(19, "订单取消后仍然发货", "发起方在订单出库前提交取消并收到受理提示，随后系统仍发货并产生退回运费争议。", "CANCELLATION_FAILURE", MEDIUM, "CANCEL_ORDER", "35.00", "家居收纳箱 2 个", "取消申请已被受理，要求关闭订单并免除退回运费。", "DISAGREE", "对方认为取消申请提交时仓库已经完成出库。"),
                    template(20, "商品参数宣传与检测结果不符", "页面宣称达到指定性能参数，第三方检测和实际使用结果均明显低于宣传值，双方对检测方法存在争议。", "SPECIFICATION_MISMATCH", HIGH, "RETURN_REFUND", "1899.00", "空气净化器 1 台", "核心性能未达到宣传标准，要求退货退款并核验参数依据。", "DISAGREE", "对方不认可现有检测条件，认为结果不能代表标准工况。"));

    public List<SimulatedExternalDisputeTemplate> all() {
        return TEMPLATES;
    }

    public int size() {
        return TEMPLATES.size();
    }

    public SimulatedExternalDisputeTemplate get(int templateNo) {
        if (templateNo < 1 || templateNo > TEMPLATES.size()) {
            throw new IllegalArgumentException("templateNo must be between 1 and " + TEMPLATES.size());
        }
        return TEMPLATES.get(templateNo - 1);
    }

    private static SimulatedExternalDisputeTemplate template(
            int number,
            String title,
            String description,
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
