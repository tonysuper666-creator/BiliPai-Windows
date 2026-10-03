// BiliPai 官方示例：影视目录（数据源豆瓣移动端公开接口）
// 演示能力：grid 网格布局、宿主驱动分页（page）、详情导航（link + loadDetail）、
// 声明式缓存、HTTP 自定义头。安装后无需任何配置即可浏览。
//
// 为什么用豆瓣接口：api.bilibili.com 的 PGC 列表端点有风控（返回 HTML 风控页），
// bangumi.tv 在部分运营商网络（尤其蜂窝网络）无法直连；
// m.douban.com/rexxar 是豆瓣移动端网页在用的公开接口，大陆直连稳定，
// 只要求带 m.douban.com 的 Referer 和常规浏览器 UA。
var DOUBAN = 'https://m.douban.com/rexxar/api/v2';
var HEADERS = {
  'User-Agent': 'Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 BiliPaiExample/1.2',
  'Referer': 'https://m.douban.com/'
};
var PAGE_SIZE = 24;

window.BiliPaiPlugin = {
  id: 'bilipai.official.bangumi',
  title: '影视目录（官方示例）',
  version: '1.2.0',
  author: 'BiliPai',
  description: '影视浏览示例：动画/剧集分类网格、无限滚动、条目详情，演示网格布局、分页与详情导航。',
  permissions: ['NETWORK'],
  detailFunctionName: 'loadDetail',
  detailCacheDuration: 3600,
  modules: [
    {
      id: 'browse',
      title: '影视目录',
      description: '按分类浏览剧集，滚动到底自动翻页。',
      functionName: 'loadBrowse',
      cacheDuration: 600,
      layout: 'grid',
      params: [
        {
          name: 'category',
          title: '分类',
          type: 'enum',
          defaultValue: '日本动画',
          options: [
            { title: '日本动画', value: '日本动画' },
            { title: '国产动画', value: '国产动画' },
            { title: '欧美动画', value: '欧美动画' },
            { title: '热门剧集', value: '' }
          ]
        },
        { name: 'page', title: '页码', type: 'page' }
      ]
    }
  ],
  loadBrowse: loadBrowse,
  loadDetail: loadDetail
};

function doubanGet(path) {
  var response = BiliPai.http.get(DOUBAN + path, HEADERS);
  if (response.code < 200 || response.code >= 300) {
    throw new Error('请求失败: HTTP ' + response.code);
  }
  var payload = JSON.parse(response.body || '{}');
  if (payload.code && payload.code !== 0) {
    throw new Error('接口返回错误: ' + (payload.localized_message || payload.msg || payload.code));
  }
  return payload;
}

function entryToItem(entry) {
  if (!entry || entry.id == null) return null;
  var rating = entry.rating && entry.rating.value > 0 ? entry.rating.value : null;
  var parts = [];
  if (rating) parts.push('★ ' + rating);
  if (entry.card_subtitle) parts.push(String(entry.card_subtitle));
  var pic = entry.pic || {};
  return {
    id: 'douban-' + entry.id,
    title: entry.title || ('条目 ' + entry.id),
    description: parts.join(' · '),
    coverUrl: pic.large || pic.normal || pic.medium || entry.cover_url || '',
    type: 'video',
    link: 'tv:' + entry.id
  };
}

async function loadBrowse(params) {
  var category = String((params && params.category) || '').trim();
  var page = Math.max(1, Number(params && params.page) || 1);
  var start = (page - 1) * PAGE_SIZE;

  var path = '/tv/recommend?refresh=0&start=' + start + '&count=' + PAGE_SIZE;
  if (category) {
    path += '&tags=' + encodeURIComponent(category);
  }

  var payload = doubanGet(path);
  var items = [];
  (payload.items || []).forEach(function (entry) {
    var item = entryToItem(entry);
    if (item) items.push(item);
  });
  if (page > 1 && items.length === 0) {
    throw new Error('没有更多内容了');
  }
  BiliPai.log('目录第 ' + page + ' 页 ' + items.length + ' 条');
  return items;
}

async function loadDetail(params) {
  var link = String((params && params.link) || '');
  if (link.indexOf('tv:') !== 0) {
    throw new Error('无法识别的详情链接');
  }
  var subjectId = link.substring('tv:'.length);

  var subject = doubanGet('/tv/' + subjectId);
  var rating = subject.rating && subject.rating.value > 0 ? subject.rating.value : null;
  var infoLines = [];
  var metaParts = [];
  if (rating) metaParts.push('评分 ★ ' + rating);
  if (subject.card_subtitle) metaParts.push(String(subject.card_subtitle));
  if (metaParts.length > 0) infoLines.push(metaParts.join('\n'));
  if (subject.genres && subject.genres.length > 0) infoLines.push('类型：' + subject.genres.join(' / '));
  if (subject.directors && subject.directors.length > 0) infoLines.push('导演：' + subject.directors.join(' / '));
  if (subject.actors && subject.actors.length > 0) {
    infoLines.push('主演：' + subject.actors.slice(0, 6).join(' / '));
  }
  if (subject.intro) infoLines.push(subject.intro);

  var pic = subject.pic || {};
  var items = [
    {
      id: 'douban-info-' + subjectId,
      title: subject.title || ('条目 ' + subjectId),
      description: infoLines.join('\n\n'),
      coverUrl: pic.large || pic.normal || subject.cover_url || '',
      type: 'video'
    }
  ];
  BiliPai.log('详情 ' + subjectId + ' 加载完成');
  return items;
}
