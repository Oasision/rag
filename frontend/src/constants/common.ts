import { transformRecordToOption } from '@/utils/common';

export const yesOrNoRecord: Record<CommonType.YesOrNo, App.I18n.I18nKey> = {
  Y: 'common.yesOrNo.yes',
  N: 'common.yesOrNo.no'
};

export const yesOrNoOptions = transformRecordToOption(yesOrNoRecord);

export const enableStatusOptions = [
  { label: '启用', value: 1 },
  { label: '禁用', value: 0 }
];

export const chunkSize = 5 * 1024 * 1024;

export const uploadAcceptExtensions = [
  'pdf',
  'doc',
  'docx',
  'txt',
  'ppt',
  'pptx',
  'md',
  'xlsx',
  'xls',
  'csv',
  'jpg',
  'jpeg',
  'png',
  'bmp',
  'gif',
  'tif',
  'tiff',
  'webp',
  'mp3',
  'wav',
  'flac',
  'aac',
  'ogg',
  'wma',
  'm4a',
  'mp4',
  'avi',
  'mov',
  'wmv',
  'mkv',
  'webm',
  'm4v'
];
export const uploadIconExtensions = [
  'pdf',
  'doc',
  'docx',
  'txt',
  'ppt',
  'pptx',
  'md',
  'xlsx',
  'xls',
  'csv',
  'jpg',
  'jpeg',
  'png',
  'bmp',
  'gif',
  'tif',
  'tiff',
  'webp',
  'mp3',
  'wav',
  'flac',
  'aac',
  'ogg',
  'wma',
  'm4a',
  'mp4',
  'avi',
  'mov',
  'wmv',
  'mkv',
  'webm',
  'm4v'
];
export const uploadAccept = uploadAcceptExtensions.map(ext => `.${ext}`).join(',');
