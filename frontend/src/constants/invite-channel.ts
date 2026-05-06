export const inviteChannelConfig = {
  officialAccountName: '黄逸飞',
  replyKeywords: ['RAG'],
  qrCodeImageUrl: ''
} as const;

export function buildInviteChannelGuide() {
  return [
    '获取 RAG知识库 邀请码方式：\n',
    `通过已注册用户生成\n `,
    '收到邀请码后，回到注册页继续完成注册\n'
  ].join('\n');
}

export function buildInviteCodeShareMessage(shareLink: string, inviteCode: string) {
  return ['RAG知识库正在内测，欢迎来体验。', `邀请码：${inviteCode}`, `注册链接：${shareLink}`, ''].join('\n');
}
