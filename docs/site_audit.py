import requests,re,urllib.parse
from html.parser import HTMLParser
class P(HTMLParser):
 def __init__(self): super().__init__();self.links=[];self.scripts=[]
 def handle_starttag(self,t,attrs):
  a=dict(attrs)
  if t=="a" and "href" in a:self.links.append(a["href"])
  if t=="script" and "src" in a:self.scripts.append(a["src"])
for site in ["https://archivebate.com/","https://notfans.com/","https://www.freepornvideos.xxx/"]:
 r=requests.get(site,timeout=20,headers={"User-Agent":"Mozilla/5.0"})
 text=r.text;p=P();p.feed(text)
 print("\n",site,len(text),"scripts",len(p.scripts),"api_count",text.lower().count("/api/"),"video_count",text.lower().count("video"))
 print("scriptpaths",[(urllib.parse.urlparse(x).path.rsplit("/",1)[-1])[:80] for x in p.scripts][:15])
 print("internal_video_links",len([x for x in p.links if any(y in x for y in ["/videos/","/video/","/watch/"])]))
 print("embedded_data",[(key,key in text) for key in ["__NEXT_DATA__","__NUXT__","__INITIAL_STATE__","application/ld+json","videoUrl","playUrl","contentUrl"]])
