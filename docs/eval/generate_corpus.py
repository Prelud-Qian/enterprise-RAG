# -*- coding: utf-8 -*-
"""评测语料生成 + 上传（可复现评测的第一步）：
1. 生成 4 份仿真企业文档 docx（内容在 DOCS 常量里，修改后重跑即可换语料）
2. 登录 admin/admin123 → 新建知识库"评测语料库" → 上传全部文档
3. 输出 KB_ID 与 TOKEN，供 eval.py 使用

用法: python generate_corpus.py --（先启动应用并确保账号存在）"""
import json
import sys
import io
import zipfile
import urllib.request

sys.stdout.reconfigure(encoding="utf-8")
BASE = "http://localhost:9090"


def http(method, path, body=None, token=None, content_type="application/json"):
    data = body if isinstance(body, bytes) else (json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None)
    headers = {"Content-Type": content_type}
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=300) as r:
            return r.status, json.loads(r.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode("utf-8"))


def make_docx(paragraphs):
    doc = ("<?xml version='1.0' encoding='UTF-8' standalone='yes'?>"
           '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>')
    for p in paragraphs:
        doc += f"<w:p><w:r><w:t>{p}</w:t></w:r></w:p>"
    doc += '<w:sectPr/></w:body></w:document>'
    ct = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
          '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
          '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
          '<Default Extension="xml" ContentType="application/xml"/>'
          '<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>'
          '</Types>')
    rels = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
            '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
            '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>'
            '</Relationships>')
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        z.writestr("[Content_Types].xml", ct)
        z.writestr("_rels/.rels", rels)
        z.writestr("word/document.xml", doc)
    return buf.getvalue()


def multipart(fields, filename, content):
    boundary = "----EvalBoundary7MA4YWxk"
    body = b""
    for k, v in fields.items():
        body += f"--{boundary}\r\nContent-Disposition: form-data; name=\"{k}\"\r\n\r\n{v}\r\n".encode()
    body += (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{filename}\"\r\n"
             f"Content-Type: application/octet-stream\r\n\r\n").encode()
    body += content + f"\r\n--{boundary}--\r\n".encode()
    return body, "multipart/form-data; boundary=" + boundary


DOCS = {
    "员工手册.docx": [
        "员工手册（2026年版）",
        "第一章 公司概况",
        "第一条 本公司成立于2010年，主营企业级协同办公软件研发与销售，总部位于杭州，在全国设有十二个分支机构。公司倡导简单、开放、共赢的企业文化，鼓励员工在工作中持续学习与创新。",
        "第二章 入职管理",
        "第二条 新员工入职时应携带身份证、学历学位证书、离职证明原件，到人力资源部办理入职手续。入职当日起三十日内，公司与员工签订劳动合同并缴纳社会保险与住房公积金。",
        "第三条 新员工试用期为三个月，试用期内表现突出者可申请提前转正。试用期考核由直属主管与人力资源部共同完成，考核不合格者公司有权解除劳动合同。",
        "第三章 考勤与休假",
        "第四条 公司实行标准工时制，工作时间为周一至周五上午九点至下午六点，中午十二点至一点为午休时间。员工每日需通过考勤系统完成两次签到。",
        "第五条 员工入职满一年后，每年享有五天带薪年假。工作年限每增加一年，年假增加一天，上限为十五天。年假可以分次使用，单次使用不得少于半天。未使用的年假可结转至次年三月底，逾期作废。",
        "第六条 员工因病需要休假的，凭二级以上医院开具的病假证明办理病假手续。病假在一个月内的，按本人基本工资的百分之八十发放病假工资。",
        "第四章 薪酬与福利",
        "第七条 公司实行十三薪制，即每年十二月份发放双倍月薪。年终奖根据公司年度经营业绩与个人绩效考核结果综合确定，一般在次年春节前发放。",
        "第八条 公司为员工缴纳五险一金，并额外购买商业补充医疗保险。员工入职满两年后，可参加公司组织的年度健康体检。",
        "第五章 行为规范",
        "第九条 员工应保守公司商业秘密，未经授权不得向任何第三方泄露公司经营数据、客户信息、源代码等技术资料。违反保密义务造成公司损失的，公司有权依法追究其法律责任。",
        "第十条 员工离职应提前三十日书面通知公司，办理工作交接与办公资产归还手续后方可离职。离职员工的竞业限制与保密义务在离职后继续有效。",
    ],
    "差旅报销制度.docx": [
        "差旅报销管理制度（2026年版）",
        "第一章 总则",
        "第一条 为规范员工出差行为，合理控制差旅成本，特制定本制度。本制度适用于公司全体员工因公出差的申请、审批与费用报销。",
        "第二章 出差申请",
        "第二条 员工出差前须在办公系统提交出差申请单，注明出差事由、目的地、预计天数与费用预算，经部门负责人审批后生效。单次出差超过七天的，须报分管副总裁审批。",
        "第三条 出差结束后三个工作日内，员工应完成出差报告并在报销系统提交费用报销申请，逾期未提交的财务部有权退回不予受理。",
        "第三章 交通与住宿标准",
        "第四条 高铁车票按二等座标准报销，飞机票按经济舱标准报销。因公需要乘坐一等座或商务舱的，须事先经部门负责人书面批准。",
        "第五条 住宿标准按城市等级划分：一线城市每晚不超过四百元，二线城市每晚不超过三百元，其他城市每晚不超过二百元。超出标准部分由员工自行承担。",
        "第六条 市内交通费按实报销，单日限额五十元。出租车费用需在发票背面注明起止地点与事由，否则财务部不予报销。",
        "第四章 补贴与报销要求",
        "第七条 出差期间的伙食补贴标准为每人每天一百元，按实际出差天数计算，与工资一同发放，无需提供发票。",
        "第八条 所有报销票据必须为税务机关监制的正规发票，抬头为公司的全称。电子发票需提供发票查验平台的真伪验证结果。",
        "第九条 报销款项在财务部审核通过后十个工作日内支付到员工工资卡。对虚报、多报费用的行为，公司将按照员工手册相关规定严肃处理。",
    ],
    "信息安全管理制度.docx": [
        "信息安全管理制度（2026年版）",
        "第一章 账号与密码",
        "第一条 员工使用的办公系统账号由信息部统一开通，一人一账号，严禁多人共用或转借他人使用。员工离职时，账号在离职当日二十四时前注销。",
        "第二条 密码长度不得少于十位，须同时包含大写字母、小写字母、数字与特殊字符中的三类，且每九十天必须更换一次。禁止使用生日、手机号等易猜测信息作为密码。",
        "第二章 数据分级与保护",
        "第三条 公司数据分为公开、内部、机密、绝密四个等级。客户合同、财务数据、源代码属于机密级，仅授权岗位人员可以访问，不得通过个人邮箱、网盘等外部渠道传输。",
        "第四条 机密级以上文件在公司内部传输必须使用加密工具，对外发送需经部门负责人与信息部双重审批，并在发送后二十四小时内上报发送记录。",
        "第三章 终端与网络安全",
        "第五条 办公电脑必须安装公司统一部署的杀毒软件与终端管理客户端，禁止私自关闭安全软件或卸载管控程序，违者按违规操作处理。",
        "第六条 严禁在办公网络内使用无线路由器、随身热点等私接网络设备。需要远程办公的，必须使用公司提供的虚拟专用网络接入，禁止直接暴露内部系统到公网。",
        "第四章 上网行为管理",
        "第七条 办公时间禁止访问与工作无关的娱乐、游戏、博彩类网站。公司网络审计系统会记录员工的网络访问日志，日志保留期限不少于六个月。",
        "第八条 严禁在办公设备上安装盗版软件或来路不明的程序。因安装不明软件导致病毒爆发或数据泄露的，相关责任人承担全部损失。",
    ],
    "绩效考核办法.docx": [
        "绩效考核管理办法（2026年版）",
        "第一章 考核目的与原则",
        "第一条 为客观评价员工工作表现，激励员工持续提升绩效，特制定本办法。考核遵循公平、公正、公开的原则，考核结果与薪酬调整、晋升、培训直接挂钩。",
        "第二章 考核周期与方式",
        "第二条 绩效考核分为月度考核、季度考核与年度考核。月度考核关注工作量与完成度，季度考核关注目标达成情况，年度考核综合全年表现进行评级。",
        "第三条 考核采用员工自评与主管评分相结合的方式，自评占百分之二十，主管评分占百分之八十。研发岗位需额外进行代码质量评审，评审结果占主管评分的百分之三十。",
        "第三章 考核等级与分布",
        "第四条 考核结果分为S、A、B、C、D五个等级。其中S级代表卓越，占比不超过百分之十；A级代表优秀，占比不超过百分之三十；B级代表称职；C级代表待改进；D级代表不合格。",
        "第五条 连续两个季度考核为D级的员工，公司将安排绩效改进计划，改进期为两个月。改进期满仍未达标的，公司有权依法解除劳动合同。",
        "第四章 结果应用",
        "第六条 年度考核为S级或A级的员工，优先获得晋升提名与薪资调整资格。年度考核为A级以上的员工，年终奖系数不低于一点二。",
        "第七条 员工对考核结果有异议的，可在结果公布后五个工作日内向人力资源部提交书面申诉，人力资源部应在十个工作日内组织复核并反馈结论。",
    ],
}

# 1. 登录
_, r = http("POST", "/api/auth/login", {"username": "admin", "password": "admin123"})
token = r["data"]["token"]

# 2. 新建知识库
_, r = http("POST", "/api/kb", {"name": "评测语料库", "description": "eval corpus"}, token)
kb_id = r["data"]["id"]
print("KB_ID=", kb_id)

# 3. 上传 4 份文档
for fname, paras in DOCS.items():
    body, ct = multipart({"kbId": str(kb_id)}, fname, make_docx(paras))
    code, r = http("POST", "/api/documents/upload", body, token, content_type=ct)
    d = r.get("data") or {}
    print(f"UPLOAD {fname}: code={code} status={d.get('status')} docId={d.get('id')} chunks={d.get('chunkCount')}")

print("TOKEN=", token)
