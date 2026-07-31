#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""从 iOS 页面元素清单文档生成 POST /api/page-objects/import 的 JSON。"""
import json

APP_ID = "def7638b-defd-4c2a-b28a-d7283743673b"
PLATFORM = "IOS"
DEFAULT_TIMEOUT = 10

def el(name, type_, value, description=None):
    st = type_.lower().replace("_", "-") if type_ == "accessibility_id" else type_.lower()
    return {
        "name": name,
        "selector": value,
        "selectorType": st,
        "description": description or name,
        "timeout": DEFAULT_TIMEOUT,
    }

# 1. Settings
Settings_SideMenu = {
    "pagePath": "Settings_SideMenu",
    "name": "设置-侧边栏",
    "elements": {
        "SIDE_MENU_BUTTON": el("SIDE_MENU_BUTTON", "xpath", "//XCUIElementTypeButton[@name='icon menu dark']"),
        "CLICK_LOGIN": el("CLICK_LOGIN", "xpath", "//XCUIElementTypeButton[@name='点击登录']"),
        "HOMEPAGE": el("HOMEPAGE", "xpath", "//XCUIElementTypeButton[@name='主页']"),
        "SETTINGS": el("SETTINGS", "xpath", "//XCUIElementTypeStaticText[@name='设置']"),
    },
}

Settings_SettingsPage = {
    "pagePath": "Settings_SettingsPage",
    "name": "设置页",
    "elements": {
        "SETTINGS_TITLE": el("SETTINGS_TITLE", "xpath", "//XCUIElementTypeStaticText[@name='设置']"),
    },
}

Settings_MyAccount = {
    "pagePath": "Settings_MyAccount",
    "name": "我的账号",
    "elements": {
        "MY_ACCOUNT_ENTRY": el("MY_ACCOUNT_ENTRY", "xpath", "//XCUIElementTypeStaticText[@name='我的账号']"),
        "MY_ACCOUNT_TITLE": el("MY_ACCOUNT_TITLE", "xpath", "//XCUIElementTypeStaticText[@name='我的账号']"),
        "BACK_BUTTON": el("BACK_BUTTON", "xpath", "//XCUIElementTypeButton[@name='ic back']"),
        "STEAM_ACCOUNT_ADD_BUTTON": el("STEAM_ACCOUNT_ADD_BUTTON", "xpath", "//XCUIElementTypeButton[contains(@name,'添加')]"),
        "STEAM_ACCOUNT_SWITCH_BUTTON": el("STEAM_ACCOUNT_SWITCH_BUTTON", "xpath", "//XCUIElementTypeButton[@name='img steam switch']"),
        "VIOLATION_DIALOG": el("VIOLATION_DIALOG", "xpath", "//XCUIElementTypeStaticText[contains(@name,'违规行为') or contains(@name,'禁止绑定')]"),
        "STEAM_BACK_BUTTON": el("STEAM_BACK_BUTTON", "xpath", "//XCUIElementTypeButton[@name='ic back']"),
        "LOGIN_INFO_INVALID_1": el("LOGIN_INFO_INVALID_1", "xpath", "(//XCUIElementTypeStaticText[@name='登录信息失效'])[1]"),
        "LOGIN_INFO_INVALID_2": el("LOGIN_INFO_INVALID_2", "xpath", "(//XCUIElementTypeStaticText[@name='登录信息失效'])[2]"),
        "UPDATE_INFO_BUTTON_1": el("UPDATE_INFO_BUTTON_1", "xpath", "(//XCUIElementTypeStaticText[@name='更新信息'])[1]"),
        "VERIFY_AND_RETURN": el("VERIFY_AND_RETURN", "xpath", "//XCUIElementTypeNavigationBar[@name='请完成验证后返回']"),
        "NETWORK_DIFFICULT": el("NETWORK_DIFFICULT", "xpath", "//XCUIElementTypeStaticText[@name='网络访问困难？']"),
        "RETURN_BUTTON": el("RETURN_BUTTON", "xpath", "//XCUIElementTypeButton[@name='ic back']"),
        "STEAM_HELP_TEXT": el("STEAM_HELP_TEXT", "xpath", "//XCUIElementTypeStaticText[@name='Steam 手机应用用户可以通过扫描二维码登录。']"),
        "GOT_IT": el("GOT_IT", "xpath", "//XCUIElementTypeStaticText[@name='知道了']"),
    },
}

Settings_Personalinfo = {
    "pagePath": "Settings_Personalinfo",
    "name": "个人信息",
    "elements": {
        "PERSONAL_ACCOUNT_BUTTON": el("PERSONAL_ACCOUNT_BUTTON", "xpath", "//XCUIElementTypeTable/XCUIElementTypeCell[1]/XCUIElementTypeOther/XCUIElementTypeOther"),
        "PERSONAL_PROFILE_TITLE": el("PERSONAL_PROFILE_TITLE", "xpath", "//XCUIElementTypeStaticText[@name='个人资料']"),
        "AVATAR_BUTTON": el("AVATAR_BUTTON", "xpath", "//XCUIElementTypeStaticText[@name='头像']"),
        "COMPLETE_BUTTON": el("COMPLETE_BUTTON", "xpath", "//XCUIElementTypeStaticText[@name='完成']"),
        "AVATAR_LIMIT_7_DAYS": el("AVATAR_LIMIT_7_DAYS", "xpath", "//XCUIElementTypeTable/XCUIElementTypeCell[7]"),
        "NICKNAME_BUTTON": el("NICKNAME_BUTTON", "xpath", "//XCUIElementTypeStaticText[@name='昵称']"),
        "MODIFY_NICKNAME_TITLE": el("MODIFY_NICKNAME_TITLE", "xpath", "//XCUIElementTypeStaticText[@name='修改昵称']"),
        "NICKNAME_INPUT_EMPTY": el("NICKNAME_INPUT_EMPTY", "xpath", "//XCUIElementTypeStaticText[@name='请输入昵称']"),
        "NICKNAME_INPUT_WITH_VALUE": el("NICKNAME_INPUT_WITH_VALUE", "xpath", "//XCUIElementTypeTextView[@value]"),
        "SAVE_BUTTON": el("SAVE_BUTTON", "xpath", "//XCUIElementTypeButton[@name='保存']"),
        "NICKNAME_OCCUPIED": el("NICKNAME_OCCUPIED", "xpath", "//XCUIElementTypeStaticText[contains(@name,'昵称已被占用')]"),
        "NICKNAME_LIMIT_7_DAYS": el("NICKNAME_LIMIT_7_DAYS", "xpath", "//XCUIElementTypeStaticText[@name='7日内仅可修改1次昵称']"),
        "PHONE_NUMBER_BUTTON": el("PHONE_NUMBER_BUTTON", "xpath", "//XCUIElementTypeStaticText[@name='手机']"),
        "CHANGE_PHONE_TITLE": el("CHANGE_PHONE_TITLE", "xpath", "//XCUIElementTypeStaticText[@name='更换手机号']"),
        "PHONE_INPUT": el("PHONE_INPUT", "xpath", "//XCUIElementTypeTextField[@value='请输入你的手机号']"),
        "NEXT_BUTTON": el("NEXT_BUTTON", "xpath", "//XCUIElementTypeStaticText[@name='下一步']"),
        "INPUT_NEW_PHONE_TITLE": el("INPUT_NEW_PHONE_TITLE", "xpath", "//XCUIElementTypeStaticText[@name='输入要绑定的手机号']"),
        "SEND_VERIFICATION_CODE": el("SEND_VERIFICATION_CODE", "xpath", "//XCUIElementTypeStaticText[@name='发送验证码']"),
        "AI_VERIFICATION_POPUP": el("AI_VERIFICATION_POPUP", "xpath", "//XCUIElementTypeOther[@name='ai-验证码']/XCUIElementTypeOther[2]"),
        "CLOSE_AI_VERIFICATION": el("CLOSE_AI_VERIFICATION", "xpath", "//XCUIElementTypeButton[@name='icon update close']"),
        "USER_CANCEL_VERIFICATION": el("USER_CANCEL_VERIFICATION", "xpath", "//XCUIElementTypeStaticText[@name='用户取消验证']"),
        "INPUT_OLD_PHONE_TITLE": el("INPUT_OLD_PHONE_TITLE", "xpath", "//XCUIElementTypeStaticText[@name='输入原手机号']"),
        "GENDER_BUTTON": el("GENDER_BUTTON", "xpath", "//XCUIElementTypeStaticText[@name='性别']"),
        "GENDER_PICKER_MAN": el("GENDER_PICKER_MAN", "xpath", "//XCUIElementTypePickerWheel[@value='男']"),
        "GENDER_PICKER_WOMAN": el("GENDER_PICKER_WOMAN", "xpath", "//XCUIElementTypePickerWheel[@value='女']"),
        "CONFIRM_BUTTON": el("CONFIRM_BUTTON", "xpath", "//XCUIElementTypeStaticText[@name='确认']"),
        "BIRTHDAY_BUTTON": el("BIRTHDAY_BUTTON", "xpath", "//XCUIElementTypeStaticText[@name='生日']"),
        "YEAR_PICKER": el("YEAR_PICKER", "xpath", "//XCUIElementTypePickerWheel[contains(@value,'年')]"),
        "MONTH_PICKER": el("MONTH_PICKER", "xpath", "//XCUIElementTypePickerWheel[contains(@value,'月')]"),
        "DAY_PICKER": el("DAY_PICKER", "xpath", "//XCUIElementTypePickerWheel[contains(@value,'日')]"),
        "SIGNATURE_BUTTON": el("SIGNATURE_BUTTON", "xpath", "//XCUIElementTypeStaticText[@name='个性签名']"),
        "MODIFY_SIGNATURE_TITLE": el("MODIFY_SIGNATURE_TITLE", "xpath", "//XCUIElementTypeStaticText[@name='修改个性签名']"),
        "SIGNATURE_INPUT_EMPTY": el("SIGNATURE_INPUT_EMPTY", "xpath", "//XCUIElementTypeStaticText[@name='介绍一下自己']"),
        "SIGNATURE_INPUT_WITH_VALUE": el("SIGNATURE_INPUT_WITH_VALUE", "xpath", "//XCUIElementTypeTextView[@value]"),
        "EMAIL_BUTTON": el("EMAIL_BUTTON", "xpath", "//XCUIElementTypeStaticText[@name='邮箱']"),
        "HARVEST_EDIT_BUTTON": el("HARVEST_EDIT_BUTTON", "xpath", "(//XCUIElementTypeStaticText[@name='编辑'])[1]"),
        "MODIFY_EMAIL_TITLE": el("MODIFY_EMAIL_TITLE", "xpath", "//XCUIElementTypeStaticText[@name='修改邮箱地址']"),
        "EMAIL_INPUT_EMPTY": el("EMAIL_INPUT_EMPTY", "xpath", "//XCUIElementTypeStaticText[@name='请输入邮箱']"),
        "EMAIL_INPUT_WITH_VALUE": el("EMAIL_INPUT_WITH_VALUE", "xpath", "//XCUIElementTypeTextView[@value]"),
        "SHIPPING_ADDRESS_BUTTON": el("SHIPPING_ADDRESS_BUTTON", "xpath", "//XCUIElementTypeStaticText[@name='收货地址']"),
        "MY_SHIPPING_ADDRESS_TITLE": el("MY_SHIPPING_ADDRESS_TITLE", "xpath", "//XCUIElementTypeStaticText[@name='我的收货地址']"),
        "NO_SHIPPING_ADDRESS": el("NO_SHIPPING_ADDRESS", "xpath", "//XCUIElementTypeStaticText[@name='暂无收货地址']"),
        "ADD_SHIPPING_ADDRESS": el("ADD_SHIPPING_ADDRESS", "xpath", "//XCUIElementTypeStaticText[@name='新增收货地址']"),
        "EDIT_ADDRESS_NAVBAR": el("EDIT_ADDRESS_NAVBAR", "xpath", "//XCUIElementTypeNavigationBar[@name='编辑地址']"),
        "EDIT_ADDRESS_TITLE": el("EDIT_ADDRESS_TITLE", "xpath", "//XCUIElementTypeStaticText[@name='编辑地址']"),
        "SAVE_ADDRESS_BUTTON": el("SAVE_ADDRESS_BUTTON", "xpath", "//XCUIElementTypeButton[@name='保存']"),
        "FILL_NAME_PROMPT": el("FILL_NAME_PROMPT", "xpath", "//XCUIElementTypeStaticText[@name='请填写姓名']"),
        "RECIPIENT_NAME_INPUT": el("RECIPIENT_NAME_INPUT", "xpath", "//XCUIElementTypeTextField[@value='收货人姓名']"),
        "VALID_PHONE_PROMPT": el("VALID_PHONE_PROMPT", "xpath", "//XCUIElementTypeStaticText[@name='请输入正确的手机号']"),
        "REGION_SELECT_BUTTON": el("REGION_SELECT_BUTTON", "xpath", "//XCUIElementTypeButton[contains(@name,'地区 选择省 / 市 / 区')]"),
        "SELECT_ONE": el("SELECT_ONE", "xpath", "//XCUIElementTypeButton[@name='请选择']"),
        "SELECT_PROVINCE": el("SELECT_PROVINCE", "xpath", "(//XCUIElementTypeButton[@name='请选择'])[1]"),
        "SHANGHAI_CITY": el("SHANGHAI_CITY", "xpath", "(//XCUIElementTypeButton[@name='上海市'])[1]"),
        "SELECT_CITY": el("SELECT_CITY", "xpath", "(//XCUIElementTypeButton[@name='请选择'])[2]"),
        "SHANGHAI_CITY_OPTION": el("SHANGHAI_CITY_OPTION", "xpath", "(//XCUIElementTypeButton[@name='上海市'])[2]"),
        "SELECT_DISTRICT": el("SELECT_DISTRICT", "xpath", "(//XCUIElementTypeButton[@name='请选择'])[3]"),
        "YANGPU_DISTRICT": el("YANGPU_DISTRICT", "xpath", "//XCUIElementTypeButton[@name='杨浦区']"),
        "CONFIRM_REGION_BUTTON": el("CONFIRM_REGION_BUTTON", "xpath", "//XCUIElementTypeButton[@name='确认']"),
        "FILL_DETAIL_ADDRESS_PROMPT": el("FILL_DETAIL_ADDRESS_PROMPT", "xpath", "//XCUIElementTypeStaticText[@name='请填写详细地址']"),
        "DETAIL_ADDRESS_INPUT": el("DETAIL_ADDRESS_INPUT", "xpath", "//XCUIElementTypeTextView[@value='街道门牌、楼层房间号等信息']"),
        "DEFAULT_ADDRESS_SWITCH": el("DEFAULT_ADDRESS_SWITCH", "xpath", "//XCUIElementTypeSwitch[@value='0']"),
        "EDIT_BUTTON": el("EDIT_BUTTON", "xpath", "//XCUIElementTypeStaticText[@name='编辑']"),
        "RECIPIENT_PHONE_INPUT": el("RECIPIENT_PHONE_INPUT", "xpath", "//XCUIElementTypeTextField[@value='收货人手机号']"),
    },
}

# 1.5 OtherFeatures - 部分元素（完整列表见文档）
Settings_OtherFeatures = {
    "pagePath": "Settings_OtherFeatures",
    "name": "其他功能",
    "elements": {
        "MY_INTEREST": el("MY_INTEREST", "xpath", "//XCUIElementTypeStaticText[@name='我的兴趣']"),
        "MY_INTEREST_BACK": el("MY_INTEREST_BACK", "xpath", "//XCUIElementTypeButton[@name='ic back']"),
        "CHOOSE_INTERESTED_GAME": el("CHOOSE_INTERESTED_GAME", "xpath", "//XCUIElementTypeStaticText[@name='选择感兴趣的游戏 ']"),
        "GAME_CSGO_1": el("GAME_CSGO_1", "xpath", "//XCUIElementTypeImage[@name='login_game_csgo_bg']"),
        "GAME_DOTA2_2": el("GAME_DOTA2_2", "xpath", "//XCUIElementTypeImage[@name='login_game_dota_bg']"),
        "GAME_STATUS_SELECTED_btn1": el("GAME_STATUS_SELECTED_btn1", "xpath", "(//XCUIElementTypeImage[@name='login_game_status_selected'])[1]"),
        "GAME_STATUS_SELECTED_btn2": el("GAME_STATUS_SELECTED_btn2", "xpath", "(//XCUIElementTypeImage[@name='login_game_status_selected'])[2]"),
        "GAME_STATUS_UNSELECTED_btn1": el("GAME_STATUS_UNSELECTED_btn1", "xpath", "(//XCUIElementTypeImage[@name='login_game_status_unselected'])[1]"),
        "GAME_STATUS_UNSELECTED_btn2": el("GAME_STATUS_UNSELECTED_btn2", "xpath", "(//XCUIElementTypeImage[@name='login_game_status_unselected'])[2]"),
        "FINISH_BUTTON": el("FINISH_BUTTON", "xpath", "//XCUIElementTypeStaticText[@name='完成']"),
        "DOTA2_HOME": el("DOTA2_HOME", "xpath", "//XCUIElementTypeStaticText[@name='DOTA2']"),
        "CS_HOME": el("CS_HOME", "xpath", "//XCUIElementTypeStaticText[@name='CS']"),
        "COLLEGE_INFO": el("COLLEGE_INFO", "xpath", "//XCUIElementTypeStaticText[@name='高校认证']"),
        "PERFECT_WORLD_COLLEGE_AUTH": el("PERFECT_WORLD_COLLEGE_AUTH", "xpath", "//XCUIElementTypeOther[@name='完美世界电竞高校认证']"),
        "CERTIFY_NOW": el("CERTIFY_NOW", "xpath", "//XCUIElementTypeStaticText[@name='立即认证']"),
        "COLLEGE_INFO_AUTH_TITLE": el("COLLEGE_INFO_AUTH_TITLE", "xpath", "//XCUIElementTypeStaticText[@name='高校信息认证']"),
        "QUICK_CHANNEL": el("QUICK_CHANNEL", "xpath", "//XCUIElementTypeButton[@name='highschool cer code']"),
        "VERIFICATION_CODE_INPUT": el("VERIFICATION_CODE_INPUT", "xpath", "//XCUIElementTypeTextField[@value=' 请输入学信网在线验证码']"),
        "AGREED_CHECKBOX": el("AGREED_CHECKBOX", "xpath", "//XCUIElementTypeButton[@name=' 已详细阅读并同意']"),
        "CERTIFY_NOTICE": el("CERTIFY_NOTICE", "xpath", "//XCUIElementTypeStaticText[@name='认证注意事项']"),
        "CERTIFY_NOTICE_TITLE": el("CERTIFY_NOTICE_TITLE", "xpath", "//XCUIElementTypeStaticText[@name='认证注意事项']"),
        "BACK_BUTTON_UNICODE": el("BACK_BUTTON_UNICODE", "xpath", "//XCUIElementTypeStaticText[@name=\"\\uE903\"]"),
        "HOW_TO_GET_VERIFICATION_CODE": el("HOW_TO_GET_VERIFICATION_CODE", "xpath", "//XCUIElementTypeStaticText[@name='如何获取学信网在线验证码？']"),
        "SUBMIT_BUTTON": el("SUBMIT_BUTTON", "xpath", "//XCUIElementTypeButton[@name='提交']"),
        "CERTIFY_FAILED": el("CERTIFY_FAILED", "xpath", "//XCUIElementTypeStaticText[@name='认证失败，请填写正确的16位或12位验证码']"),
        "MANUAL_CHANNEL": el("MANUAL_CHANNEL", "xpath", "//XCUIElementTypeButton[@name='highschool cer manual']"),
        "SCHOOL_NAME": el("SCHOOL_NAME", "xpath", "//XCUIElementTypeStaticText[@name='学校名称']"),
        "ALREADY_CERTIFIED_OR_EXPIRED": el("ALREADY_CERTIFIED_OR_EXPIRED", "xpath", "//XCUIElementTypeStaticText[@name='您被判定为毕业校友或认证已过期，如果在校生认证已过期请重新认证']"),
        "PRIVACY_SETTING": el("PRIVACY_SETTING", "xpath", "//XCUIElementTypeStaticText[@name='隐私设置']"),
        "PRIVACY_SETTING_TITLE": el("PRIVACY_SETTING_TITLE", "xpath", "//XCUIElementTypeStaticText[@name='隐私设置']"),
        "CS_FRIEND_CODE_DISPLAY": el("CS_FRIEND_CODE_DISPLAY", "xpath", "//XCUIElementTypeStaticText[@name='CS 好友码显示']"),
        "CS_HIGHLIGHT_DISPLAY": el("CS_HIGHLIGHT_DISPLAY", "xpath", "//XCUIElementTypeStaticText[@name='CS 高光时刻显示']"),
        "CS_REAL_TIME_MATCH_DISPLAY": el("CS_REAL_TIME_MATCH_DISPLAY", "xpath", "//XCUIElementTypeStaticText[@name='CS 实时比赛状态显示']"),
        "CS_RANKING_DISPLAY": el("CS_RANKING_DISPLAY", "xpath", "//XCUIElementTypeStaticText[@name='CS 国服排行榜信息显示']"),
        "DOTA2_RECORD_DISPLAY": el("DOTA2_RECORD_DISPLAY", "xpath", "//XCUIElementTypeStaticText[@name='DOTA2 战绩显示']"),
        "OMG_RECORD_DISPLAY": el("OMG_RECORD_DISPLAY", "xpath", "//XCUIElementTypeStaticText[@name='OMG 4+2 战绩显示']"),
        "COLLEGE_AUTH_INFO_DISPLAY": el("COLLEGE_AUTH_INFO_DISPLAY", "xpath", "//XCUIElementTypeStaticText[@name='高校认证信息显示']"),
        "ALLOW_STRANGER_MESSAGE": el("ALLOW_STRANGER_MESSAGE", "xpath", "//XCUIElementTypeStaticText[@name='允许陌生人向我发起私信']"),
        "ANONYMOUS_PURCHASE": el("ANONYMOUS_PURCHASE", "xpath", "//XCUIElementTypeStaticText[@name='完美开箱匿名购买钥匙']"),
        "BLACK_ENTER_BTN": el("BLACK_ENTER_BTN", "xpath", "//XCUIElementTypeTable/XCUIElementTypeOther/XCUIElementTypeOther"),
        "BLACK_LIST": el("BLACK_LIST", "xpath", "(//XCUIElementTypeStaticText[@name='黑名单管理'])[2]"),
        "BLACK_LIST_TITLE": el("BLACK_LIST_TITLE", "xpath", "//XCUIElementTypeStaticText[@name='黑名单管理']"),
        "NO_BLACKED_USER": el("NO_BLACKED_USER", "xpath", "//XCUIElementTypeStaticText[@name='暂无拉黑用户']"),
        "VIDEO_SETTING": el("VIDEO_SETTING", "xpath", "//XCUIElementTypeStaticText[@name='视频播放设置']"),
        "VIDEO_SETTING_TITLE": el("VIDEO_SETTING_TITLE", "xpath", "//XCUIElementTypeStaticText[@name='视频播放设置']"),
        "ALWAYS_AUTO_PLAY": el("ALWAYS_AUTO_PLAY", "xpath", "//XCUIElementTypeStaticText[@name='任何网络下都自动播放']"),
        "WIFI_AUTO_PLAY": el("WIFI_AUTO_PLAY", "xpath", "//XCUIElementTypeStaticText[@name='仅 Wi-Fi 下自动播放']"),
        "NEVER_AUTO_PLAY": el("NEVER_AUTO_PLAY", "xpath", "//XCUIElementTypeStaticText[@name='关闭自动播放']"),
        "MESSAGE_NOTIFICATION": el("MESSAGE_NOTIFICATION", "xpath", "//XCUIElementTypeStaticText[@name='消息通知']"),
        "MESSAGE_NOTIFICATION_TITLE": el("MESSAGE_NOTIFICATION_TITLE", "xpath", "//XCUIElementTypeStaticText[@name='消息通知']"),
        "GO_ENABLE": el("GO_ENABLE", "xpath", "//XCUIElementTypeTable/XCUIElementTypeButton"),
        "CS_NOTIFICATION": el("CS_NOTIFICATION", "xpath", "//XCUIElementTypeStaticText[@name='CS']"),
        "CS_RECORD_PUSH": el("CS_RECORD_PUSH", "xpath", "//XCUIElementTypeStaticText[@name='CS 战绩数据推送']"),
        "CS_PERFECT_MOMENT_PUSH": el("CS_PERFECT_MOMENT_PUSH", "xpath", "//XCUIElementTypeStaticText[@name='CS 完美时刻推送']"),
        "CS_GOD_KILL_PUSH": el("CS_GOD_KILL_PUSH", "xpath", "//XCUIElementTypeStaticText[@name='CS 灭神榜推送']"),
        "CS_OBSERVER_PUSH": el("CS_OBSERVER_PUSH", "xpath", "//XCUIElementTypeStaticText[@name='CS 观将台推送']"),
        "PAC_PUSH": el("PAC_PUSH", "xpath", "//XCUIElementTypeStaticText[@name='完美平台PAC推送']"),
        "DOTA2_NOTIFICATION": el("DOTA2_NOTIFICATION", "xpath", "//XCUIElementTypeStaticText[@name='DOTA2']"),
        "DOTA2_RECORD_PUSH": el("DOTA2_RECORD_PUSH", "xpath", "//XCUIElementTypeStaticText[@name='DOTA2 战绩推送']"),
        "DOTA2_PERFECT_MOMENT_PUSH": el("DOTA2_PERFECT_MOMENT_PUSH", "xpath", "//XCUIElementTypeStaticText[@name='DOTA2 完美时刻推送']"),
        "GENERAL": el("GENERAL", "xpath", "//XCUIElementTypeStaticText[@name='综合']"),
        "MAIN_TEAM_MATCH_PUSH": el("MAIN_TEAM_MATCH_PUSH", "xpath", "//XCUIElementTypeStaticText[@name='CS/DOTA2主队比赛推送']"),
        "VERSION_UPDATE": el("VERSION_UPDATE", "xpath", "//XCUIElementTypeStaticText[@name='版本更新']"),
        "VERSION_BUTTON": el("VERSION_BUTTON", "xpath", "//XCUIElementTypeButton[starts-with(@name,'v')]"),
        "CONTACT_US": el("CONTACT_US", "xpath", "//XCUIElementTypeStaticText[@name='联系我们']"),
        "PERFECT_WORLD_ESPORTS": el("PERFECT_WORLD_ESPORTS", "xpath", "(//XCUIElementTypeStaticText[@name='完美世界电竞'])[1]"),
        "PROBLEM_FEEDBACK": el("PROBLEM_FEEDBACK", "xpath", "//XCUIElementTypeStaticText[@name='问题反馈']"),
        "TITLE_INPUT": el("TITLE_INPUT", "xpath", "(//XCUIElementTypeTextField[@value='请输入'])[1]"),
        "PROBLEM_CATEGORY": el("PROBLEM_CATEGORY", "xpath", "//XCUIElementTypeStaticText[@name='问题分类']"),
        "PERFECT_WORLD_QUESTION": el("PERFECT_WORLD_QUESTION", "xpath", "//XCUIElementTypeStaticText[@name='完美世界电竞问题']"),
        "PAC_APPEAL": el("PAC_APPEAL", "xpath", "//XCUIElementTypeStaticText[@name='PAC申诉（仅PAC用户使用）']"),
        "REPORT_CHEAT": el("REPORT_CHEAT", "xpath", "//XCUIElementTypeStaticText[@name='举报作弊开挂']"),
        "CLOSE_POPUP": el("CLOSE_POPUP", "xpath", "//XCUIElementTypeOther[@name='文稿']/XCUIElementTypeOther[1]/XCUIElementTypeImage[2]"),
        "STEAM_ID_INPUT": el("STEAM_ID_INPUT", "xpath", "(//XCUIElementTypeTextField[@value='请输入'])[2]"),
        "QQ_INPUT": el("QQ_INPUT", "xpath", "(//XCUIElementTypeTextField[@value='请输入'])[3]"),
        "PROBLEM_DESCRIPTION_INPUT": el("PROBLEM_DESCRIPTION_INPUT", "xpath", "//XCUIElementTypeStaticText[@name=\"'您好，为了更好地解决您的问题,请告诉我们以下内容：\"]"),
        "UPLOAD_IMAGE": el("UPLOAD_IMAGE", "xpath", "//XCUIElementTypeOther[@name='完美世界电竞']/XCUIElementTypeImage"),
        "PHOTO_LIBRARY": el("PHOTO_LIBRARY", "xpath", "//XCUIElementTypeButton[@name='照片图库']"),
        "USER_AGREEMENT": el("USER_AGREEMENT", "xpath", "//XCUIElementTypeStaticText[@name='用户协议']"),
        "USER_AGREEMENT_TITLE": el("USER_AGREEMENT_TITLE", "xpath", "//XCUIElementTypeStaticText[@name='完美世界电竞用户协议']"),
        "RIGHTS_ABANDON": el("RIGHTS_ABANDON", "xpath", "//XCUIElementTypeStaticText[@name='14.2权利放弃']"),
        "USER_AGREEMENT_START": el("USER_AGREEMENT_START", "xpath", "//XCUIElementTypeStaticText[@name='本《完美世界电竞用户协议》（以下简称']"),
        "PRIVACY_POLICY": el("PRIVACY_POLICY", "xpath", "//XCUIElementTypeStaticText[@name='个人信息保护政策']"),
        "PRIVACY_POLICY_TITLE": el("PRIVACY_POLICY_TITLE", "xpath", "//XCUIElementTypeStaticText[@name='完美世界电竞个人信息保护政策']"),
        "PERSONAL_INFO_LIST": el("PERSONAL_INFO_LIST", "xpath", "//XCUIElementTypeStaticText[@name='个人信息收集清单']"),
        "PERSONAL_INFO_LIST_TITLE": el("PERSONAL_INFO_LIST_TITLE", "xpath", "//XCUIElementTypeStaticText[@name='个人信息收集清单']"),
        "AVATAR": el("AVATAR", "xpath", "//XCUIElementTypeStaticText[@name='头像']"),
        "NAME": el("NAME", "xpath", "//XCUIElementTypeStaticText[@name='名字']"),
        "PHONE_NUMBER": el("PHONE_NUMBER", "xpath", "//XCUIElementTypeStaticText[@name='手机号']"),
        "GENDER": el("GENDER", "xpath", "//XCUIElementTypeStaticText[@name='性别']"),
        "SIGNATURE": el("SIGNATURE", "xpath", "//XCUIElementTypeStaticText[@name='个性签名']"),
        "ADDRESS": el("ADDRESS", "xpath", "//XCUIElementTypeStaticText[@name='地址']"),
        "LOCATION": el("LOCATION", "xpath", "//XCUIElementTypeStaticText[@name='位置']"),
        "IMAGE_AND_VIDEO": el("IMAGE_AND_VIDEO", "xpath", "//XCUIElementTypeStaticText[@name='图片和视频']"),
    },
}

# 2. MyAssets
MyAssets_MainTabs = {
    "pagePath": "MyAssets_MainTabs",
    "name": "我的资产-主Tab",
    "elements": {
        "EXPERIENCE_CARD": el("EXPERIENCE_CARD", "xpath", "//XCUIElementTypeButton[@name='体验卡']"),
        "ALIPAY_VOUCHER": el("ALIPAY_VOUCHER", "xpath", "//XCUIElementTypeButton[@name='支付宝代金券']"),
        "APP_COUPON": el("APP_COUPON", "xpath", "//XCUIElementTypeButton[@name='APP优惠券']"),
        "BACKPACK": el("BACKPACK", "xpath", "//XCUIElementTypeButton[@name='背包']"),
        "ITEM_EXCHANGE": el("ITEM_EXCHANGE", "xpath", "//XCUIElementTypeButton[@name='道具兑换']"),
    },
}

MyAssets_ExperienceCardStatusTabs = {
    "pagePath": "MyAssets_ExperienceCardStatusTabs",
    "name": "体验卡状态Tab",
    "elements": {
        "TO_ACTIVATE": el("TO_ACTIVATE", "xpath", "//XCUIElementTypeStaticText[@name='待激活']"),
        "ACTIVATED": el("ACTIVATED", "xpath", "//XCUIElementTypeStaticText[@name='已激活']"),
        "EXPIRED": el("EXPIRED", "xpath", "//XCUIElementTypeStaticText[@name='已过期']"),
    },
}

MyAssets_CouponStatusTabs = {
    "pagePath": "MyAssets_CouponStatusTabs",
    "name": "优惠券状态Tab",
    "elements": {
        "UNUSED": el("UNUSED", "xpath", "//XCUIElementTypeStaticText[@name='未使用']"),
        "USED": el("USED", "xpath", "//XCUIElementTypeStaticText[@name='已使用']"),
        "EXPIRED": el("EXPIRED", "xpath", "//XCUIElementTypeStaticText[@name='已过期']"),
    },
}

MyAssets_ItemExchange = {
    "pagePath": "MyAssets_ItemExchange",
    "name": "道具兑换",
    "elements": {
        "CODE_INPUT": el("CODE_INPUT", "xpath", "//XCUIElementTypeTextField[@value='在此输入16位激活码']"),
        "ACTIVATE_BUTTON": el("ACTIVATE_BUTTON", "xpath", "//XCUIElementTypeButton[@name='立即激活']"),
        "INVALID_CODE_MESSAGE": el("INVALID_CODE_MESSAGE", "xpath", "//XCUIElementTypeStaticText[@name='该CODE码不存在']"),
    },
}

# 3. 通用 - MainPage, LoginPage, VerificationPage
MainPage = {
    "pagePath": "MainPage",
    "name": "主页面",
    "elements": {
        "LOGIN_BUTTON": el("LOGIN_BUTTON", "xpath", "//XCUIElementTypeStaticText[@name=\"立即登录\"]"),
        "APP_TITLE": el("APP_TITLE", "xpath", "//XCUIElementTypeStaticText[@name=\"完美世界电竞\"]"),
        "BOTTOM_NAVIGATION": el("BOTTOM_NAVIGATION", "xpath", "//XCUIElementTypeTabBar"),
        "DISCOVER_TAB": el("DISCOVER_TAB", "xpath", "//XCUIElementTypeButton[@name=\"发现\"]"),
        "MATCH_TAB": el("MATCH_TAB", "xpath", "//XCUIElementTypeButton[@name=\"赛事\"]"),
    },
}

LoginPage = {
    "pagePath": "LoginPage",
    "name": "登录页",
    "elements": {
        "CLOSE_BUTTON": el("CLOSE_BUTTON", "xpath", "//XCUIElementTypeButton[@name=\"close\" or @name=\"关闭\"]"),
        "APP_LOGO": el("APP_LOGO", "xpath", "//XCUIElementTypeImage[@name=\"app_logo\"]"),
        "PHONE_DISPLAY": el("PHONE_DISPLAY", "xpath", "//XCUIElementTypeStaticText[contains(@name,\"137****6864\")]"),
        "SERVICE_PROVIDER": el("SERVICE_PROVIDER", "xpath", "//XCUIElementTypeStaticText[@name=\"中国移动提供认证服务\"]"),
        "AGREEMENT_CHECKBOX": el("AGREEMENT_CHECKBOX", "xpath", "//XCUIElementTypeButton[@name=\"checkbox\" or @name=\"同意协议\"]"),
        "ONE_CLICK_LOGIN_BUTTON": el("ONE_CLICK_LOGIN_BUTTON", "xpath", "//XCUIElementTypeButton[@name=\"一键登录\"]"),
        "SWITCH_PHONE_LINK": el("SWITCH_PHONE_LINK", "xpath", "//XCUIElementTypeStaticText[@name=\"切换手机号\"]"),
        "OTHER_LOGIN_TITLE": el("OTHER_LOGIN_TITLE", "xpath", "//XCUIElementTypeStaticText[@name=\"其他登录方式\"]"),
        "STEAM_LOGIN_BUTTON": el("STEAM_LOGIN_BUTTON", "xpath", "//XCUIElementTypeButton[@name=\"Steam\"]"),
        "APPLE_LOGIN_BUTTON": el("APPLE_LOGIN_BUTTON", "xpath", "//XCUIElementTypeButton[@name=\"Apple\"]"),
        "PHONE_INPUT_FIELD": el("PHONE_INPUT_FIELD", "xpath", "//XCUIElementTypeTextField[@name=\"phone_number\" or @name=\"手机号\"]"),
        "SEND_CODE_BUTTON": el("SEND_CODE_BUTTON", "xpath", "//XCUIElementTypeButton[@name=\"发送验证码\"]"),
    },
}

VerificationPage = {
    "pagePath": "VerificationPage",
    "name": "验证码页",
    "elements": {
        "CODE_INPUT_FIELD": el("CODE_INPUT_FIELD", "xpath", "//XCUIElementTypeTextField[@name=\"verification_code\" or @name=\"验证码\"]"),
        "CONFIRM_BUTTON": el("CONFIRM_BUTTON", "xpath", "//XCUIElementTypeButton[@name=\"确认\" or @name=\"登录\"]"),
        "RESEND_BUTTON": el("RESEND_BUTTON", "xpath", "//XCUIElementTypeButton[@name=\"重新发送\"]"),
        "COUNTDOWN_TEXT": el("COUNTDOWN_TEXT", "xpath", "//XCUIElementTypeStaticText[contains(@name,\"秒后重新发送\")]"),
    },
}

# 4. 测试代码内联元素 - 按小节分页
Inline_SidebarLogin = {
    "pagePath": "Inline_SidebarLogin",
    "name": "侧边栏/登录(内联)",
    "elements": {
        "SIDEBAR_MENU_ALT": el("SIDEBAR_MENU_ALT", "xpath", "//XCUIElementTypeButton[contains(@name, 'menu')]"),
        "LOGOUT_BUTTON": el("LOGOUT_BUTTON", "xpath", "(//XCUIElementTypeButton[@name=\"退出登录\"])[1]"),
        "LOGOUT_TEXT": el("LOGOUT_TEXT", "xpath", "(//XCUIElementTypeStaticText[@name=\"退出登录\"])[1]"),
        "CONFIRM_POPUP": el("CONFIRM_POPUP", "xpath", "//XCUIElementTypeButton[@name=\"确定\"]"),
        "AGREED_READ": el("AGREED_READ", "xpath", "//XCUIElementTypeButton[@name='我已阅读并同意....']"),
        "SEND_VERIFICATION_CODE_BTN": el("SEND_VERIFICATION_CODE_BTN", "xpath", "//XCUIElementTypeButton[@name='发送验证码']"),
        "INPUT_VERIFICATION_CODE": el("INPUT_VERIFICATION_CODE", "xpath", "//XCUIElementTypeTextField[@value='请输入验证码']"),
        "LOGIN_NOW": el("LOGIN_NOW", "xpath", "//XCUIElementTypeStaticText[@name=\"立即登录\"]"),
    },
}

Inline_MessageFollow = {
    "pagePath": "Inline_MessageFollow",
    "name": "消息/关注(内联)",
    "elements": {
        "MY_MESSAGE": el("MY_MESSAGE", "xpath", "//XCUIElementTypeStaticText[@name='我的消息']"),
        "MESSAGE_TAB": el("MESSAGE_TAB", "xpath", "//XCUIElementTypeStaticText[@name='消息']"),
        "SEARCH_FRIEND_INPUT": el("SEARCH_FRIEND_INPUT", "xpath", "//XCUIElementTypeTextField[@value='搜索好友']"),
        "CANCEL_BUTTON": el("CANCEL_BUTTON", "xpath", "//XCUIElementTypeStaticText[@name='取消']"),
        "FOLLOW": el("FOLLOW", "xpath", "//XCUIElementTypeStaticText[@name='关注']"),
        "FOLLOW_PLUS_FIRST": el("FOLLOW_PLUS_FIRST", "xpath", "(//XCUIElementTypeStaticText[@name='+ 关注'])[1]"),
        "MUTUAL_FOLLOW": el("MUTUAL_FOLLOW", "xpath", "//XCUIElementTypeStaticText[@name='相互关注']"),
        "NO_MORE_DATA": el("NO_MORE_DATA", "xpath", "//XCUIElementTypeButton[@name='没有更多数据了']"),
    },
}

Inline_SettingsAddress = {
    "pagePath": "Inline_SettingsAddress",
    "name": "设置/收货地址/键盘(内联)",
    "elements": {
        "SELECT_PLEASE": el("SELECT_PLEASE", "xpath", "//XCUIElementTypeButton[@name='请选择']"),
        "DONE_KEYBOARD": el("DONE_KEYBOARD", "xpath", "//XCUIElementTypeStaticText[@name='完成'] 或 //XCUIElementTypeButton[@name='完成']"),
        "KEYBOARD_AREA": el("KEYBOARD_AREA", "xpath", "//XCUIElementTypeKeyboard | //XCUIElementTypeKey"),
        "PICKER_WHEEL": el("PICKER_WHEEL", "xpath", "//XCUIElementTypePickerWheel"),
        "EDIT_ADDRESS_REGION": el("EDIT_ADDRESS_REGION", "xpath", "//XCUIElementTypeOther[@name='编辑地址']/XCUIElementTypeOther[6]"),
        "TITLE_PLEASE_INPUT": el("TITLE_PLEASE_INPUT", "xpath", "//XCUIElementTypeOther[@name=\"标题 * 请输入\"]"),
        "FEEDBACK_TITLE_INPUT": el("FEEDBACK_TITLE_INPUT", "xpath", "//XCUIElementTypeOther[@name=\"完美世界电竞\"]/XCUIElementTypeTextField[1]"),
        "STEAM_64_ID_INPUT": el("STEAM_64_ID_INPUT", "xpath", "//XCUIElementTypeOther[@name=\"Steam 64位ID * 请输入\"]"),
        "STEAM_ID_FIELD": el("STEAM_ID_FIELD", "xpath", "//XCUIElementTypeOther[@name=\"完美世界电竞\"]/XCUIElementTypeTextField[2]"),
        "QQ_PLEASE_INPUT": el("QQ_PLEASE_INPUT", "xpath", "//XCUIElementTypeOther[@name=\"QQ号 请输入\"]"),
        "QQ_FIELD": el("QQ_FIELD", "xpath", "//XCUIElementTypeOther[@name=\"完美世界电竞\"]/XCUIElementTypeTextField[3]"),
        "ADD_BUTTON": el("ADD_BUTTON", "xpath", "//XCUIElementTypeButton[@name=\"添加\"]"),
        "PLAYER_SUGGESTION": el("PLAYER_SUGGESTION", "xpath", "//XCUIElementTypeStaticText[@name='玩家建议其它等相关问题']"),
    },
}

Inline_PrivacySwitch = {
    "pagePath": "Inline_PrivacySwitch",
    "name": "隐私开关(内联)",
    "elements": {
        "SWITCH_BY_NAME": el("SWITCH_BY_NAME", "xpath", "//XCUIElementTypeSwitch[@name='{name}']"),
        "SWITCH_ON": el("SWITCH_ON", "ios_predicate", "name == \"{name}\" AND label == \"{label}\" AND value == \"1\""),
        "SWITCH_OFF": el("SWITCH_OFF", "ios_predicate", "name == \"{name}\" AND label == \"{label}\" AND value == \"0\""),
    },
}

Inline_OutfitOther = {
    "pagePath": "Inline_OutfitOther",
    "name": "装扮/其他(内联)",
    "elements": {
        "OUTFIT_SELECTED_ICON": el("OUTFIT_SELECTED_ICON", "xpath", "//XCUIElementTypeImage[@name='icon_premade_selected']"),
        "SEARCH_FIELD": el("SEARCH_FIELD", "xpath", "//XCUIElementTypeTextField[@value=\"搜索...\"]"),
    },
}

PAGE_OBJECTS = [
    Settings_SideMenu,
    Settings_SettingsPage,
    Settings_MyAccount,
    Settings_Personalinfo,
    Settings_OtherFeatures,
    MyAssets_MainTabs,
    MyAssets_ExperienceCardStatusTabs,
    MyAssets_CouponStatusTabs,
    MyAssets_ItemExchange,
    MainPage,
    LoginPage,
    VerificationPage,
    Inline_SidebarLogin,
    Inline_MessageFollow,
    Inline_SettingsAddress,
    Inline_PrivacySwitch,
    Inline_OutfitOther,
]

def main():
    payload = {
        "appId": APP_ID,
        "platform": PLATFORM,
        "pageObjects": PAGE_OBJECTS,
    }
    out_path = "/tmp/esports_ios_import.json"
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(payload, f, ensure_ascii=False, indent=2)
    print("Wrote", out_path, "| pages:", len(PAGE_OBJECTS))
    total_elements = sum(len(p["elements"]) for p in PAGE_OBJECTS)
    print("Total elements:", total_elements)

if __name__ == "__main__":
    main()
