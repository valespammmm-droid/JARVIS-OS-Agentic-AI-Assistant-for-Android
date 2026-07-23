import urllib.request
import urllib.parse
import xml.etree.ElementTree as ET

def test_google_news_rss(query: str):
    headers = {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36'
    }
    encoded = urllib.parse.quote(query)
    url = f"https://news.google.com/rss/search?q={encoded}&hl=en-IN&gl=IN&ceid=IN:en"
    
    req = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(req, timeout=5) as resp:
        xml_data = resp.read().decode('utf-8')
        root = ET.fromstring(xml_data)
        
        items = []
        for item in root.findall('./channel/item')[:5]:
            title = item.find('title').text if item.find('title') is not None else ''
            link = item.find('link').text if item.find('link') is not None else ''
            pub_date = item.find('pubDate').text if item.find('pubDate') is not None else ''
            items.append({"title": title, "link": link, "pubDate": pub_date})
            
        print(f"GOOGLE NEWS RSS RESULTS FOR '{query}':")
        for i in items:
            print(f"- {i['title']} | {i['pubDate']}")

if __name__ == "__main__":
    test_google_news_rss("Chief Minister of Tamil Nadu")
