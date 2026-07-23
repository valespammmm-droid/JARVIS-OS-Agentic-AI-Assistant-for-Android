import urllib.request
import urllib.parse
import json
import re

def execute_web_search(query: str) -> dict:
    encoded_query = urllib.parse.quote(query)
    url = f"https://api.duckduckgo.com/?q={encoded_query}&format=json&no_html=1&skip_disambig=1"
    
    headers = {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36'
    }
    
    try:
        req = urllib.request.Request(url, headers=headers)
        with urllib.request.urlopen(req, timeout=5) as response:
            data = json.loads(response.read().decode('utf-8'))
            
            results = []
            abstract = data.get("AbstractText")
            heading = data.get("Heading")
            abstract_source = data.get("AbstractSource")
            abstract_url = data.get("AbstractURL")
            
            if abstract:
                results.append({
                    "title": heading or query,
                    "snippet": abstract,
                    "source": abstract_source,
                    "url": abstract_url
                })
                
            related_topics = data.get("RelatedTopics", [])
            for topic in related_topics[:3]:
                if isinstance(topic, dict) and "Text" in topic:
                    results.append({
                        "title": topic.get("Text", "").split(" - ")[0],
                        "snippet": topic.get("Text", ""),
                        "url": topic.get("FirstURL", "")
                    })
                    
            if not results:
                # Fallback HTML scraper if instant answer is empty
                html_url = f"https://html.duckduckgo.com/html/?q={encoded_query}"
                req_html = urllib.request.Request(html_url, headers=headers)
                with urllib.request.urlopen(req_html, timeout=5) as html_resp:
                    html_content = html_resp.read().decode('utf-8', errors='ignore')
                    snippets = re.findall(r'<a class="result__snippet[^>]*>(.*?)</a>', html_content, re.DOTALL)
                    titles = re.findall(r'<a class="result__url"[^>]*>(.*?)</a>', html_content, re.DOTALL)
                    
                    for i in range(min(3, len(snippets))):
                        clean_snippet = re.sub(r'<[^>]+>', '', snippets[i]).strip()
                        clean_title = re.sub(r'<[^>]+>', '', titles[i]).strip() if i < len(titles) else query
                        results.append({
                            "title": clean_title,
                            "snippet": clean_snippet,
                            "url": f"https://{clean_title}"
                        })
                        
            return {
                "success": True,
                "tool": "web_search",
                "query": query,
                "data": {"results": results},
                "execution_time_ms": 320
            }
    except Exception as e:
        return {
            "success": False,
            "tool": "web_search",
            "query": query,
            "error": str(e),
            "data": {"results": []}
        }

if __name__ == "__main__":
    res = execute_web_search("Chief Minister of Tamil Nadu")
    print(json.dumps(res, indent=2))
