import urllib.request
import re
try:
    html = urllib.request.urlopen("https://en.wikipedia.org/wiki/Multimedia_Commands").read().decode()
    print("Length:", len(html))
except Exception as e:
    print(e)
