import requests
from html.parser import HTMLParser
from urllib.parse import urljoin
class A(HTMLParser):
 def __init__(self):super().__init__();self.links=[]
 def handle_starttag(self,t,attrs):
  if t=="a":
   u=dict(attrs).get("href")
   if u and "view_video" in u:self.links.append(u)
home="https://www.pornhub.com/"
h={"User-Agent":"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0 Safari/537.36"}
r=requests.get(home,headers=h,timeout=15)
a=A();a.feed(r.text)
urls=list(dict.fromkeys(urljoin(home,x) for x in a.links))[:6]
for index,url in enumerate(urls):
 try:
  response=requests.get(url,headers=h,timeout=14)
  print(index,response.status_code,len(response.content))
 except Exception as e:print(index,type(e).__name__)
