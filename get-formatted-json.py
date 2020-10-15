#! /usr/bin/python

import json, sys, urllib

url = sys.argv[1]
response = urllib.urlopen(url)
parsedJson = json.loads(response.read())

print json.dumps(parsedJson, indent=4)