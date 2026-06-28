import os
import re

def process_file(filepath):
    with open(filepath, 'r', encoding='utf-8') as f:
        content = f.read()

    content = content.replace('result.stdout()', 'result.readStdout()')
    content = content.replace('result.stderr()', 'result.readStderr()')
    
    with open(filepath, 'w', encoding='utf-8') as f:
        f.write(content)

for root, dirs, files in os.walk(r'c:\Users\katwa\OneDrive\Desktop\code-condenser\zap\src\main\java\com\zapproxy\filter'):
    for f in files:
        if f.endswith('.java'):
            process_file(os.path.join(root, f))
