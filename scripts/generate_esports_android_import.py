#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生成「完美世界电竞」Android 页面对象导入 JSON。
用法：
  1. 将下方 __APP_ID__ 改为你的应用 ID（或通过环境变量 APP_ID 传入）。
  2. 运行: python3 scripts/generate_esports_android_import.py > esports_android_import.json
  3. 若未在脚本中改 appId，可: sed -i '' 's/__APP_ID__/你的appId/g' esports_android_import.json
  4. 调用接口: curl -X POST http://127.0.0.1:3000/api/page-objects/import -H "Content-Type: application/json" -d @esports_android_import.json
"""
import json
import os

# 应用 ID：可在此写死，或通过环境变量 APP_ID 传入
APP_ID = os.environ.get("APP_ID", "__APP_ID__")
PLATFORM = "ANDROID"


def elem(name, type_, value, description=""):
    """type: id|xpath|className|text -> selectorType: id|xpath|className|name"""
    st = "name" if type_ == "text" else type_
    return {
        "name": name,
        "selector": value,
        "selectorType": st,
        "description": description or name,
        "timeout": 10,
    }


def page(page_path, name, elements_list):
    """elements_list: [(element_name, type, value), ...]"""
    elements = {}
    for (ename, t, v) in elements_list:
        elements[ename] = elem(ename, t, v)
    return {"pagePath": page_path, "name": name, "elements": elements}


# ---------- 1. 设置模块 ----------
PAGES = [
    page("Settings_SideMenu", "设置-侧边栏", [
        ("SIDE_MENU_BUTTON", "id", "com.pwrd.steam.esports:id/sideView"),
        ("CLICK_LOGIN", "xpath", "//android.widget.TextView[@text='点击登录']"),
        ("HOMEPAGE", "xpath", "//android.widget.TextView[@resource-id='com.pwrd.steam.esports:id/tv_mine_homepage']"),
        ("SETTINGS", "xpath", "//android.widget.TextView[@resource-id='com.pwrd.steam.esports:id/tv_tool_mame' and @text='设置']"),
    ]),
    page("Settings_SettingsPage", "设置页", [
        ("SETTINGS_TITLE", "xpath", "//android.widget.TextView[@resource-id='com.pwrd.steam.esports:id/tv_title']"),
        ("LOGOUT_BUTTON", "xpath", "//android.widget.TextView[@resource-id='com.pwrd.steam.esports:id/tv_logout']"),
    ]),
    page("Settings_MyAccount", "我的账号", [
        ("MY_ACCOUNT_ENTRY", "xpath", "//android.widget.TextView[@text='我的账号']"),
        ("MY_ACCOUNT_TITLE", "xpath", "//android.widget.TextView[@resource-id='com.pwrd.steam.esports:id/tv_title']"),
        ("PERFECT_WORLD_ACCOUNT", "xpath", "//android.widget.TextView[@text='完美世界电竞账号']"),
        ("STEAM_ACCOUNT_ADD_BUTTON", "xpath", "//android.widget.ImageView[@resource-id='com.pwrd.steam.esports:id/steam_account_state_icon_iv']"),
        ("VIOLATION_DIALOG", "xpath", "//android.widget.TextView[contains(@text,'违规行为') or contains(@text,'禁止绑定')]"),
        ("TITLE_BAR_BACK_ICON", "xpath", "//android.widget.ImageView[@resource-id='com.pwrd.steam.esports:id/title_bar_back_icon']"),
        ("LOGIN_INFO_INVALID", "xpath", "//android.widget.TextView[@text='登录信息失效']"),
        ("UPDATE_INFO_BUTTON", "xpath", "//android.widget.TextView[@text='更新信息']"),
    ]),
    page("Settings_PersonalInfo", "个人信息", [
        ("ENTER_PERSONAL_PROFILE", "xpath", "//android.widget.RelativeLayout[@resource-id='com.pwrd.steam.esports:id/account_basic_info_rl']/android.widget.ImageView[3]"),
        ("PERSONAL_PROFILE_TITLE", "xpath", "//android.widget.TextView[@resource-id='com.pwrd.steam.esports:id/tv_title']"),
        ("AVATAR_CONTAINER", "xpath", "//android.widget.RelativeLayout[@resource-id='com.pwrd.steam.esports:id/account_info_avatar_container']/android.widget.ImageView[2]"),
        ("CHOOSE_FROM_ALBUM", "xpath", "//android.widget.TextView[@resource-id='com.pwrd.steam.esports:id/tvDesc' and @text='从相册选择']"),
        ("ALBUM_FIRST_SELECT", "xpath", "(//android.widget.TextView[@resource-id='com.pwrd.steam.esports:id/tvCheck'])[1]"),
        ("BACK_ICON", "xpath", "//android.widget.ImageView[@resource-id='com.pwrd.steam.esports:id/ps_iv_left_back']"),
        ("NICKNAME_BUTTON", "xpath", "//android.widget.ScrollView/android.widget.LinearLayout/android.widget.RelativeLayout[2]/android.widget.ImageView"),
        ("NICKNAME_INPUT", "xpath", "//android.widget.EditText[@resource-id='com.pwrd.steam.esports:id/account_info_edit_et']"),
        ("SAVE_BUTTON", "xpath", "//android.widget.TextView[@resource-id='com.pwrd.steam.esports:id/tv_menu']"),
        ("NICKNAME_OCCUPIED", "xpath", "//android.widget.TextView[contains(@text,'昵称已被占用')]"),
        ("NICKNAME_LIMIT", "xpath", "//android.widget.TextView[contains(@text,'7天内仅可修改1次昵称')]"),
        ("PHONE_NUMBER_BUTTON", "xpath", "//android.widget.ScrollView/android.widget.LinearLayout/android.widget.RelativeLayout[3]/android.widget.ImageView"),
        ("CHANGE_PHONE_TITLE", "xpath", "//android.widget.TextView[@resource-id='com.pwrd.steam.esports:id/title']"),
        ("OLD_PHONE_INPUT", "xpath", "//android.widget.EditText[@resource-id='com.pwrd.steam.esports:id/phone_number_et']"),
        ("NEXT_BUTTON", "xpath", "//android.widget.TextView[@resource-id='com.pwrd.steam.esports:id/send_code_tv']"),
        ("INPUT_CONFIRM_CODE_TEXT", "xpath", "//android.widget.TextView[@resource-id='com.pwrd.steam.esports:id/input_confirm_code_text']"),
        ("GENDER_BUTTON", "xpath", "//android.widget.ScrollView/android.widget.LinearLayout/android.widget.RelativeLayout[4]/android.widget.ImageView"),
        ("GENDER_MAN", "xpath", "//android.widget.TextView[@resource-id='com.pwrd.steam.esports:id/account_dialog_man_tv']"),
        ("GENDER_WOMAN", "xpath", "//android.widget.TextView[@resource-id='com.pwrd.steam.esports:id/account_dialog_woman_tv']"),
        ("CONFIRM_BUTTON", "xpath", "//android.widget.TextView[@resource-id='com.pwrd.steam.esports:id/account_dialog_confirm_tv']"),
        ("CANCEL_BUTTON", "xpath", "//android.widget.TextView[@resource-id='com.pwrd.steam.esports:id/account_dialog_cancel_tv']"),
        ("BIRTHDAY_BUTTON", "xpath", "//android.widget.ScrollView/android.widget.LinearLayout/android.widget.RelativeLayout[5]/android.widget.ImageView"),
        ("YEAR_SELECTOR", "xpath", "//android.view.View[@resource-id='com.pwrd.steam.esports:id/year']"),
        ("MONTH_SELECTOR", "xpath", "//android.view.View[@resource-id='com.pwrd.steam.esports:id/month']"),
        ("DAY_SELECTOR", "xpath", "//android.view.View[@resource-id='com.pwrd.steam.esports:id/day']"),
        ("SUBMIT_BUTTON", "xpath", "//android.widget.Button[@resource-id='com.pwrd.steam.esports:id/btnSubmit']"),
        ("SIGNATURE_BUTTON", "xpath", "//android.widget.ScrollView/android.widget.LinearLayout/android.widget.RelativeLayout[6]/android.widget.ImageView"),
        ("EMAIL_BUTTON", "xpath", "//android.widget.ScrollView/android.widget.LinearLayout/android.widget.RelativeLayout[7]/android.widget.ImageView"),
        ("SHIPPING_ADDRESS_CONTAINER", "xpath", "//android.widget.ImageView[@resource-id='com.pwrd.steam.esports:id/account_info_shipping_address_container']"),
    ]),
    page("Settings_ShippingAddress", "收货地址", [
        ("MY_SHIPPING_ADDRESS_WEBVIEW", "xpath", "//android.webkit.WebView[@text='我的收货地址']"),
        ("NO_SHIPPING_ADDRESS", "xpath", "//android.view.View[@text='暂无收货地址']"),
        ("ADD_SHIPPING_ADDRESS", "xpath", "//android.widget.TextView[@text='新增收货地址']"),
        ("EDIT_ADDRESS_WEBVIEW", "xpath", "//android.webkit.WebView[@text='编辑地址']"),
        ("RECIPIENT_NAME_INPUT", "xpath", "//android.webkit.WebView[@text='编辑地址']/android.view.View[1]/android.view.View[1]/android.view.View[2]/android.widget.EditText"),
        ("SAVE_BUTTON", "xpath", "//android.widget.Button[@text='保存']"),
        ("FILL_NAME_PROMPT", "xpath", "//android.view.View[@text='请填写姓名']"),
        ("RECIPIENT_PHONE_INPUT", "xpath", "//android.webkit.WebView[@text='编辑地址']/android.view.View[1]/android.view.View[2]/android.view.View[2]/android.widget.EditText"),
        ("VALID_PHONE_PROMPT", "xpath", "//android.view.View[@text='请输入正确的手机号']"),
        ("SELECT_REGION_BUTTON", "xpath", "//android.widget.Button[@text='地区 选择省 / 市 / 区']"),
        ("SELECT_PROVINCE", "xpath", "(//android.widget.Button[@text='请选择'])[1]"),
        ("SHANGHAI_CITY", "xpath", "(//android.view.View[@text='上海市'])[4]"),
        ("SELECT_CITY", "xpath", "(//android.widget.Button[@text='请选择'])[2]"),
        ("SHANGHAI_CITY_OPTION", "xpath", "(//android.widget.Button[@text='上海市'])[2]"),
        ("SELECT_DISTRICT", "xpath", "(//android.widget.Button[@text='请选择'])[3]"),
        ("YANGPU_DISTRICT", "xpath", "//android.widget.Button[@text='杨浦区']"),
        ("CONFIRM_BUTTON", "xpath", "//android.widget.Button[@text='确认']"),
        ("DEFAULT_ADDRESS_CHECKBOX", "xpath", "//android.widget.CheckBox"),
    ]),
    # 1.6 OtherFeatures 取部分常用
    page("Settings_OtherFeatures", "其他功能", [
        ("MY_INTEREST", "xpath", "//android.widget.RelativeLayout[@resource-id='com.pwrd.steam.esports:id/my_interest_rl']/android.widget.ImageView"),
        ("CHOOSE_INTERESTED_GAME_TEXT", "xpath", "//android.widget.TextView[@resource-id='com.pwrd.steam.esports:id/choose_interested_game_text']"),
        ("CSGO_CHECKBOX", "xpath", "//android.widget.TextView[@resource-id='com.pwrd.steam.esports:id/csgo_choose_status_tv']"),
        ("DOTA2_CHECKBOX", "xpath", "//android.widget.TextView[@resource-id='com.pwrd.steam.esports:id/dota2_choose_status_tv']"),
        ("FINISH_BUTTON", "xpath", "//android.widget.TextView[@resource-id='com.pwrd.steam.esports:id/confirm_button']"),
        ("VERSION", "xpath", "//android.widget.TextView[@resource-id='com.pwrd.steam.esports:id/tv_version']"),
        ("CONTACT_US", "xpath", "//android.widget.TextView[@text='联系我们']"),
        ("USER_AGREEMENT", "xpath", "//android.widget.TextView[@text='用户协议']"),
        ("PRIVACY_POLICY", "xpath", "//android.widget.TextView[@text='个人信息保护政策']"),
    ]),
    # ---------- 2. 任务大厅 ----------
    page("TaskHall_SideMenu", "任务大厅-侧边栏", [
        ("SIDE_MENU_BUTTON", "id", "com.pwrd.steam.esports:id/sideView"),
        ("CLICK_LOGIN", "xpath", "//android.widget.TextView[@text='点击登录']"),
        ("HOMEPAGE", "xpath", "//android.widget.TextView[@resource-id='com.pwrd.steam.esports:id/tv_mine_homepage']"),
        ("TASK_HALL", "xpath", "//android.widget.TextView[@resource-id='com.pwrd.steam.esports:id/tv_tool_mame' and @text='任务大厅']"),
    ]),
    page("TaskHall_TaskHall", "任务大厅", [
        ("GO_TO_COMPLETE_FIRST", "xpath", "(//android.widget.TextView[@text='去完成'])[1]"),
        ("GO_TO_COMPLETE_SECOND", "xpath", "(//android.widget.TextView[@text='去完成'])[2]"),
    ]),
    page("TaskHall_Square", "广场", [
        ("SQUARE_TAB", "xpath", "//android.widget.TextView[@resource-id='com.pwrd.steam.esports:id/tvText' and @text='广场']"),
    ]),
    page("TaskHall_Popup", "弹窗", [
        ("DOTA2", "xpath", "//android.widget.Button[@text='DOTA2']"),
        ("CS", "xpath", "//android.widget.Button[@text='CS']"),
        ("CANCEL", "xpath", "//android.widget.Button[@text='取消']"),
    ]),
    # ---------- 3. 我的消息 ----------
    page("MyMessage_SideMenu", "我的消息-侧边栏", [
        ("SIDE_MENU_BUTTON", "id", "com.pwrd.steam.esports:id/sideView"),
        ("CLICK_LOGIN", "xpath", "//android.widget.TextView[@text='点击登录']"),
        ("HOMEPAGE", "xpath", "//android.widget.TextView[@resource-id='com.pwrd.steam.esports:id/tv_mine_homepage']"),
        ("MY_MESSAGE", "xpath", "//android.widget.TextView[@resource-id='com.pwrd.steam.esports:id/tv_tool_mame' and @text='我的消息']"),
    ]),
    page("MyMessage_MessagePage", "消息页", [
        ("MESSAGE_TAB", "xpath", "//android.widget.TextView[@resource-id='com.pwrd.steam.esports:id/tvText' and @text='消息']"),
        ("CONTACTS_TAB", "xpath", "//android.widget.TextView[@resource-id='com.pwrd.steam.esports:id/tvText' and @text='联系人']"),
        ("SEARCH_FRIEND", "xpath", "//android.widget.TextView[@text='搜索好友']"),
        ("SEARCH_CANCEL", "xpath", "//android.widget.TextView[@resource-id='com.pwrd.steam.esports:id/tv_cancel']"),
        ("REPLY_AND_MESSAGE", "xpath", "//android.widget.TextView[@text='回复和留言']"),
        ("FOLLOW", "xpath", "//android.widget.TextView[@text='关注']"),
        ("LIKE", "xpath", "//android.widget.TextView[@text='点赞']"),
        ("FRIEND_REQUEST", "xpath", "//android.widget.TextView[@text='好友申请']"),
        ("BACK_BUTTON", "xpath", "//android.widget.ImageView[@resource-id='com.pwrd.steam.esports:id/title_bar_back_icon']"),
    ]),
    # ---------- 6. 完美世界电竞通用 ----------
    page("MainPage", "主页面", [
        ("LOGIN_BUTTON", "text", "立即登录"),
        ("APP_TITLE", "text", "完美世界电竞"),
        ("BOTTOM_NAVIGATION", "xpath", "//android.widget.TabWidget"),
        ("DISCOVER_TAB", "xpath", "//android.widget.TextView[@text=\"发现\"]"),
        ("MATCH_TAB", "xpath", "//android.widget.TextView[@text=\"赛事\"]"),
    ]),
    page("LoginPage", "登录页", [
        ("CLOSE_BUTTON", "xpath", "//android.widget.Button[@text=\"关闭\" or @content-desc=\"关闭\"]"),
        ("APP_LOGO", "xpath", "//android.widget.ImageView[@content-desc=\"app_logo\"]"),
        ("PHONE_DISPLAY", "xpath", "//android.widget.TextView[contains(@text,\"137****6864\")]"),
        ("ONE_CLICK_LOGIN_BUTTON", "xpath", "//android.widget.Button[@text=\"一键登录\"]"),
        ("SWITCH_PHONE_LINK", "xpath", "//android.widget.TextView[@text=\"切换手机号\"]"),
        ("STEAM_LOGIN_BUTTON", "xpath", "//android.widget.Button[@text=\"Steam\"]"),
        ("APPLE_LOGIN_BUTTON", "xpath", "//android.widget.Button[@text=\"Apple\"]"),
        ("PHONE_INPUT_FIELD", "xpath", "//android.widget.EditText[@text=\"手机号\" or @hint=\"手机号\"]"),
        ("SEND_CODE_BUTTON", "xpath", "//android.widget.Button[@text=\"发送验证码\"]"),
    ]),
    page("VerificationPage", "验证码页", [
        ("CODE_INPUT_FIELD", "xpath", "//android.widget.EditText[@text=\"验证码\" or @hint=\"验证码\"]"),
        ("CONFIRM_BUTTON", "xpath", "//android.widget.Button[@text=\"确认\" or @text=\"登录\"]"),
        ("RESEND_BUTTON", "xpath", "//android.widget.Button[@text=\"重新发送\"]"),
        ("COUNTDOWN_TEXT", "xpath", "//android.widget.TextView[contains(@text,\"秒后重新发送\")]"),
    ]),
    # ---------- 4. 我的装扮 ----------
    page("MyOutfit_SideMenu", "我的装扮-侧边栏", [
        ("SIDE_MENU_BUTTON", "id", "com.pwrd.steam.esports:id/sideView"),
        ("CLICK_LOGIN", "xpath", "//android.widget.TextView[@text='点击登录']"),
        ("HOMEPAGE", "xpath", "//android.widget.TextView[@resource-id='com.pwrd.steam.esports:id/tv_mine_homepage']"),
        ("MY_OUTFIT", "xpath", "//android.widget.TextView[@resource-id='com.pwrd.steam.esports:id/tv_tool_mame' and @text='我的装扮']"),
    ]),
    page("MyOutfit_MainTabs", "我的装扮-主Tab", [
        ("AVATAR_FRAME", "xpath", "//android.widget.TextView[@text='头像框']"),
        ("AVATAR", "xpath", "//android.widget.TextView[@text='头像']"),
        ("DYNAMIC_BACKGROUND", "xpath", "//android.widget.TextView[@text='动态背景']"),
        ("BADGE", "xpath", "//android.widget.TextView[@text='徽章']"),
    ]),
    # ---------- 5. 我的资产 ----------
    page("MyAssets_SideMenu", "我的资产-侧边栏", [
        ("MY_ASSETS", "xpath", "//android.widget.TextView[@resource-id='com.pwrd.steam.esports:id/tv_tool_mame' and @text='我的资产']"),
        ("ITEM_EXCHANGE", "xpath", "//*[@resource-id='com.pwrd.steam.esports:id/tv_menu']"),
    ]),
    page("MyAssets_MainTabs", "我的资产-主Tab", [
        ("EXPERIENCE_CARD", "xpath", "//android.view.View[@text='体验卡']"),
        ("ALIPAY_VOUCHER", "xpath", "//android.view.View[@text='支付宝代金券']"),
        ("APP_COUPON", "xpath", "//android.view.View[@text='APP优惠券']"),
        ("BACKPACK", "xpath", "//android.view.View[@text='背包']"),
    ]),
    page("MyAssets_ItemExchange", "道具兑换", [
        ("CODE_INPUT", "xpath", "//input[@placeholder='在此输入16位激活码']"),
        ("ACTIVATE_BUTTON", "xpath", "//android.widget.Button[@text='立即激活']"),
        ("INVALID_CODE_MESSAGE", "xpath", "//android.view.View[@text='该CODE码不存在']"),
    ]),
]

payload = {
    "appId": APP_ID,
    "platform": PLATFORM,
    "pageObjects": PAGES,
}

if __name__ == "__main__":
    print(json.dumps(payload, ensure_ascii=False, indent=2))
