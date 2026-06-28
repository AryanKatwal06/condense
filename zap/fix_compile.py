import os
import re

def process_file(filepath):
    with open(filepath, 'r', encoding='utf-8') as f:
        content = f.read()

    # If it still has result.stdout() or result.stderr()
    if 'result.stdout()' in content or 'result.stderr()' in content:
        # Some are just passthrough:
        content = content.replace('FilterResult.passthrough(result.stdout())', 'FilterResult.passthrough(result)')
        content = content.replace('FilterResult.passthrough(result.stderr())', 'FilterResult.passthrough(result)')
        


            content = content.replace('String raw = result.stdout();', 'try (java.util.stream.Stream<String> lines = result.stdoutLines()) {')
            content = content.replace('raw.lines()', 'lines')
            # But wait, it uses raw again for fallback. Let's just use result.readStdout() for fallback?


        # Most filters:
        # String raw = result.stdout().isBlank() ? result.stderr() : result.stdout();
        # This is a common pattern.
        # Let's replace result.stdout() -> result.readStdout() for everything to make it COMPILE first.
        # Then we'll manually stream the ones that are easy.
        pass

    # To make it compile:
    content = content.replace('result.stdout()', 'result.readStdout()')
    content = content.replace('result.stderr()', 'result.readStderr()')
    
    with open(filepath, 'w', encoding='utf-8') as f:
        f.write(content)

for root, dirs, files in os.walk(r'c:\Users\katwa\OneDrive\Desktop\code-condenser\zap\src\main\java\com\zapproxy\filter'):
    for f in files:
        if f.endswith('.java'):
            process_file(os.path.join(root, f))
