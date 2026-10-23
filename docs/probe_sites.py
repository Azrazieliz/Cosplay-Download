from html.parser import HTMLParser
from urllib.parse import urlparse, urljoin
import requests
from collections import Counter

class HrefParser(HTMLParser):
    def __init__(self):
        super().__init__(); self.links=[]
    def handle_starttag(self, tag, attrs):
        if tag not in ("a","video","source"): return
        d=dict(attrs)
        if d.get("href"): self.links.append(d["href"])
        elif d.get("src"): self.links.append(d["src"])

for host in ["www.pornhub.com","notfans.com","archivebate.com","www.freepornvideos.xxx"]:
    url="https://"+host+"/"
    try:
        response=requests.get(url,timeout=20,headers={"User-Agent":"Mozilla/5.0"})
        parser=HrefParser(); parser.feed(response.text)
        urls=[urlparse(urljoin(url,a)) for a in parser.links]
        urls=[u for u in urls if u.hostname and u.hostname.endswith(host.replace("www.",""))]
        print(host,response.status_code,"links",len(urls))
        print("sections",Counter(u.path.strip("/").split("/")[0] for u in urls).most_common(12))
        print("samples",sorted({u.path.split("/")[0]+"/"+(u.path.split("/")[1] if len(u.path.split("/"))>1 else "") for u in urls})[:20])
    except Exception as e:
        print(host,repr(e))
