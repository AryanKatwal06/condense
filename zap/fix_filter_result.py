import os

def process_file(filepath):
    with open(filepath, 'r', encoding='utf-8') as f:
        content = f.read()

    # FilterResult.of(raw, ...) -> FilterResult.of(result, ...)
    content = content.replace('FilterResult.of(raw,', 'FilterResult.of(result,')
    
    # FilterResult.passthrough(result.readStdout()) -> FilterResult.passthrough(result)
    content = content.replace('FilterResult.passthrough(result.readStdout())', 'FilterResult.passthrough(result)')
    
    with open(filepath, 'w', encoding='utf-8') as f:
        f.write(content)

for root, dirs, files in os.walk(r'c:\Users\katwa\OneDrive\Desktop\code-condenser\zap\src\main\java\com\zapproxy\filter'):
    for f in files:
        if f.endswith('.java'):
            process_file(os.path.join(root, f))
