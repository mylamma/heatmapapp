export default {
  async fetch(request) {
    const url = new URL(request.url);
    const target = url.searchParams.get('url');

    if (!target) {
      return new Response('Missing url parameter', { status: 400 });
    }

    let targetUrl;
    try {
      targetUrl = new URL(target);
    } catch (e) {
      return new Response('Invalid url', { status: 400 });
    }

    // 허용 도메인: 네이버 금융(국내 종목/코스피) + 야후 파이낸스(해외지수/VIX) + 환율 API(원달러)
    const allowedHosts = [
      'polling.finance.naver.com',
      'm.stock.naver.com',
      'finance.naver.com',
      'query1.finance.yahoo.com',
      'query2.finance.yahoo.com',
      'open.er-api.com'
    ];
    if (!allowedHosts.includes(targetUrl.hostname)) {
      return new Response('Host not allowed', { status: 403 });
    }

    try {
      const resp = await fetch(targetUrl.toString(), {
        headers: {
          'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36',
          'Referer': 'https://finance.naver.com/',
          'Accept': 'application/json, text/plain, */*'
        }
      });
      const body = await resp.text();

      return new Response(body, {
        status: resp.status,
        headers: {
          'Content-Type': 'application/json; charset=utf-8',
          'Access-Control-Allow-Origin': '*'
        }
      });
    } catch (e) {
      return new Response(JSON.stringify({ error: 'fetch failed', message: String(e) }), {
        status: 502,
        headers: { 'Content-Type': 'application/json; charset=utf-8' }
      });
    }
  }
};
