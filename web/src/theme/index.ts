import type { GlobalThemeOverrides } from 'naive-ui'

/**
 * §1.3 品牌 token → Naive UI 暗色覆写。
 * 主色只允许低饱和青绿 #8FB9B4，危险 #C45C4A，成功 #7AA87A。
 */
export const themeOverrides: GlobalThemeOverrides = {
  common: {
    fontFamily:
      "'Figtree','Noto Sans SC','PingFang SC','Hiragino Sans GB','Microsoft YaHei',system-ui,-apple-system,sans-serif",
    fontFamilyMono: "'IBM Plex Mono',ui-monospace,'Cascadia Mono',Consolas,monospace",
    fontWeightStrong: '600',
    fontSize: '14px',

    // 放映厅底色
    bodyColor: '#0B0B0A',
    cardColor: '#14120F',
    modalColor: '#161310',
    popoverColor: '#1A1712',
    tableColor: '#14120F',
    actionColor: '#0F0E0C',

    // 文本
    textColorBase: '#F3F0E8',
    textColor1: '#F3F0E8',
    textColor2: '#D8D3C8',
    textColor3: '#A39E93',
    textColorDisabled: '#6F6A60',

    // 主色 = 低饱和青绿（仅主按钮与「进行中」指示）
    primaryColor: '#8FB9B4',
    primaryColorHover: '#A3C7C2',
    primaryColorPressed: '#7BA6A0',
    primaryColorSuppl: '#8FB9B4',

    infoColor: '#8FB9B4',
    infoColorHover: '#A3C7C2',
    infoColorPressed: '#7BA6A0',
    infoColorSuppl: '#8FB9B4',

    errorColor: '#C45C4A',
    errorColorHover: '#D16A57',
    errorColorPressed: '#A64A3B',
    errorColorSuppl: '#C45C4A',

    successColor: '#7AA87A',
    successColorHover: '#8DB98C',
    successColorPressed: '#669466',
    successColorSuppl: '#7AA87A',

    warningColor: '#C9A15F',
    warningColorHover: '#D6B176',
    warningColorPressed: '#B08A4C',

    // 线 / 分隔
    dividerColor: '#24221F',
    borderColor: '#2E2B26',

    // 圆角（§1.3）
    borderRadius: '8px',
    borderRadiusSmall: '6px',
  },

  Card: {
    borderRadius: '12px',
    borderColor: '#24221F',
    color: '#14120F',
    titleFontWeight: '600',
  },
  Modal: {
    borderRadius: '16px',
  },
  Popover: {
    borderRadius: '12px',
    border: '1px solid #2E2B26',
  },
  Dialog: {
    borderRadius: '16px',
  },
  Input: {
    borderRadius: '8px',
    color: '#161310',
    colorFocus: '#161310',
    colorHover: '#1A1712',
    border: '1px solid #2E2B26',
    borderHover: '1px solid #3A362F',
    borderFocus: '1px solid #7BA6A0',
    boxShadowFocus: '0 0 0 2px rgba(143,185,180,0.16)',
    placeholderColor: '#6F6A60',
    caretColor: '#8FB9B4',
    textColor: '#F3F0E8',
  },
  Select: {
    peers: {
      InternalSelection: {
        color: '#161310',
        colorActive: '#161310',
        border: '1px solid #2E2B26',
        borderHover: '1px solid #3A362F',
        borderFocus: '1px solid #7BA6A0',
        boxShadowFocus: '0 0 0 2px rgba(143,185,180,0.16)',
        placeholderColor: '#6F6A60',
        caretColor: '#8FB9B4',
        textColor: '#F3F0E8',
      },
    },
  },
  Button: {
    borderRadiusMedium: '8px',
    borderRadiusLarge: '10px',
    fontWeight: '600',
    textColorPrimary: '#0B0B0A',
    textColorHoverPrimary: '#0B0B0A',
    textColorPressedPrimary: '#0B0B0A',
    textColorFocusPrimary: '#0B0B0A',
  },
  Radio: {
    buttonColorActive: '#12201E',
    buttonBorderColorActive: '#7BA6A0',
    dotColorActive: '#8FB9B4',
  },
  Checkbox: {
    colorChecked: '#8FB9B4',
    checkMarkColor: '#0B0B0A',
  },
  Tabs: {
    tabTextColorActiveLine: '#8FB9B4',
    tabTextColorActiveBar: '#8FB9B4',
    tabColorSegment: '#161310',
    tabColorActiveSegment: '#1C1915',
    tabBorderColor: '#24221F',
  },
  Tooltip: {
    color: '#1A1712',
    border: '1px solid #2E2B26',
  },
  Dropdown: {
    color: '#1A1712',
    border: '1px solid #2E2B26',
    optionColorActive: '#26221C',
    optionTextColor: '#F3F0E8',
    optionTextColorActive: '#F3F0E8',
    optionColorHover: '#26221C',
  },
  DataTable: {
    thColor: '#14120F',
    tdColor: '#14120F',
    borderColor: '#24221F',
  },
  Empty: {
    iconColor: '#6F6A60',
    textColor: '#A39E93',
  },
  Progress: {
    railColor: '#24221F',
    color: '#8FB9B4',
    iconTextColorInfo: '#0B0B0A',
  },
  Alert: {
    borderRadius: '10px',
  },
  Tag: {
    borderRadius: '6px',
  },
}
