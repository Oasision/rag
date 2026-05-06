/** Default theme settings */
export const themeSettings: App.Theme.ThemeSetting = {
  themeScheme: 'auto',
  grayscale: false,
  colourWeakness: false,
  recommendColor: true,
  themeColor: '#2563eb',
  otherColor: { info: '#0ea5e9', success: '#22c55e', warning: '#f59e0b', error: '#ef4444' },
  isInfoFollowPrimary: true,
  resetCacheStrategy: 'close',
  layout: { mode: 'vertical', scrollMode: 'content', reverseHorizontalMix: false },
  page: { animate: true, animateMode: 'fade-slide' },
  header: { height: 56, breadcrumb: { visible: false, showIcon: true }, multilingual: { visible: false } },
  tab: { visible: false, cache: true, height: 44, mode: 'chrome' },
  fixedHeaderAndTab: true,
  sider: {
    inverted: false,
    width: 196,
    collapsedWidth: 64,
    mixWidth: 90,
    mixCollapsedWidth: 64,
    mixChildMenuWidth: 200
  },
  footer: { visible: false, fixed: false, height: 48, right: true },
  watermark: { visible: false, text: 'RAG知识库' },
  tokens: {
    light: {
      colors: {
        container: 'rgb(255, 255, 255)',
        layout: 'rgb(239, 246, 255)',
        inverted: 'rgb(0, 20, 40)',
        'base-text': 'rgb(20, 31, 48)'
      },
      boxShadow: {
        header: '0 18px 42px rgb(37, 99, 235, 0.08)',
        sider: '18px 0 46px rgb(15, 23, 42, 0.08)',
        tab: '0 14px 34px rgb(15, 23, 42, 0.08)'
      }
    },
    dark: { colors: { container: 'rgb(28, 28, 28)', layout: 'rgb(18, 18, 18)', 'base-text': 'rgb(224, 224, 224)' } }
  }
};

/**
 * Override theme settings
 *
 * If publish new version, use `overrideThemeSettings` to override certain theme settings
 */
export const overrideThemeSettings: Partial<App.Theme.ThemeSetting> = {};
