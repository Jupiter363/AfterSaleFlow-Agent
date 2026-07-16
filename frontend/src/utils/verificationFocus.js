// 文件作用：前端工程代码文件，支撑售后争议系统的页面、交互、样式或构建配置。
// 说明：本注释用于帮助读者先了解本文件职责，再继续阅读具体实现。

const CANONICAL_FOCUS_RULES = [
  {
    key: "product-page",
    pattern: /商品.{0,8}(页面|详情|描述)|页面.{0,8}(截图|快照|描述)|详情页|商品链接/u,
    text: "核对商品页面完整描述、截图或快照",
  },
  {
    key: "communication",
    pattern: /沟通记录|聊天记录|聊天截图|客服记录|协商记录|与商家.{0,8}(沟通|聊天)|与用户.{0,8}(沟通|聊天)/u,
    text: "核验用户与商家的完整沟通记录",
  },
  {
    key: "product-condition",
    pattern: /开箱|拆箱|磨损|划痕|破损|损坏|外观|瑕疵|商品.{0,6}(照片|图片|视频)|(照片|图片|视频).{0,6}(磨损|划痕|破损|损坏|瑕疵)/u,
    text: "核验商品异常照片或开箱视频，确认商品状态及形成时间",
  },
  {
    key: "logistics-signoff",
    pattern: /物流|签收|投递|派送|收货|验货|快递|包裹|开包|打开检查|开启包裹/u,
    text: "核验物流签收及投递记录，确认签收人身份、位置、时间与开箱检查间隔",
  },
  {
    key: "order",
    pattern: /订单号|订单信息|涉案商品|商品数量/u,
    text: "核对订单信息与涉案商品",
  },
  {
    key: "after-sale",
    pattern: /售后单|售后申请|售后记录|处理记录/u,
    text: "核对售后申请与处理记录",
  },
  {
    key: "respondent-attitude",
    pattern: /对方.{0,8}(回应|态度)|商家.{0,8}(回应|态度)|用户.{0,8}(回应|态度)|是否接受.{0,8}(退款|诉求)/u,
    text: "核实对方对诉求的明确回应",
  },
];

const PROCESS_FOCUS_PATTERN =
  /信息完整度|完整度已达到|提交阈值|可以提交|等待接待官|接待官.{0,12}整理|完成案件详情|进入下一步|后续流程|流程推进|ready_for_next_step|READY_PENDING_REMARK_INVITE|WAITING_FOR_REMARK|NOT_READY/iu;

// 业务位置：【前端应用】cleanFocusText：围绕 面向当事人的业务文本 计算本模块需要的派生信息，使其能够从 路由、API 和本地状态 正确进入 售后纠纷处理界面。上游：路由、API 和本地状态。下游：售后纠纷处理界面。边界：前端不拥有裁判和执行权限。
function cleanFocusText(value) {
  return String(value || "")
    .replace(/^[\s·•\-—]+/u, "")
    .replace(/^(仍然|仍|目前)?缺少(可信的|完整的|相关的)?/u, "")
    .replace(/^(请问)?(您|你)?是否有/u, "")
    .replace(/^(能否|是否可以|可否)(请)?(提供|补充)?/u, "")
    .replace(/^(请|麻烦)(您|你)?(提供|补充|说明|确认|核实|核对)?/u, "")
    .replace(/(是否可以提供|是否能提供|可以提供吗|能提供吗)$/u, "")
    .replace(/[？?。；;，,\s]+$/u, "")
    .trim();
}

// 业务位置：【前端应用】genericActionFocus：围绕 履约执行动作和工具意图 计算本模块需要的派生信息，使其能够从 路由、API 和本地状态 正确进入 售后纠纷处理界面。上游：路由、API 和本地状态。下游：售后纠纷处理界面。边界：前端不拥有裁判和执行权限。
function genericActionFocus(value) {
  let text = cleanFocusText(value);
  if (!text) return "";
  text = text
    .replace(/^获取/u, "核验")
    .replace(/^收集/u, "核验")
    .replace(/^补充/u, "核验")
    .replace(/^提供/u, "核验");
  if (/^(核验|核对|核实|确认)/u.test(text)) return text;
  return `核验${text}`;
}

// 业务位置：【前端应用】normalizeVerificationFocus：将 当前阶段业务数据 转换为稳定的接口、提示词或页面表达，避免直接暴露内部实现字段。上游：路由、API 和本地状态。下游：售后纠纷处理界面。边界：前端不拥有裁判和执行权限。
export function normalizeVerificationFocus(values) {
  const result = [];
  const seen = new Set();
  const sources = (Array.isArray(values) ? values : [])
    .map(cleanFocusText)
    .filter((value) => value && !PROCESS_FOCUS_PATTERN.test(value));
  const hasProductConditionContext = sources.some((source) =>
    /开箱|拆箱|磨损|划痕|破损|损坏|外观|瑕疵/u.test(source),
  );
  for (const source of sources) {
    if (!source) continue;
    let rule = CANONICAL_FOCUS_RULES.find((candidate) => candidate.pattern.test(source));
    if (!rule && hasProductConditionContext && /^(照片|图片|视频)$/u.test(source)) {
      rule = CANONICAL_FOCUS_RULES.find((candidate) => candidate.key === "product-condition");
    }
    const key = rule?.key || genericActionFocus(source).replace(/[\s、，,。；;：:]/gu, "");
    let text = rule?.text || genericActionFocus(source);
    if (rule?.key === "respondent-attitude") {
      const party = source.match(/商家|用户/u)?.[0] || "对方";
      text = `核实${party}对诉求的明确回应`;
    }
    if (!text || seen.has(key)) continue;
    seen.add(key);
    result.push(text);
  }
  return result;
}
