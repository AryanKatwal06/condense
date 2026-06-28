import os
import re

def process_file(filepath):
    with open(filepath, 'r', encoding='utf-8') as f:
        content = f.read()

    # Replace FilterResult.of(..., summary) -> FilterResult.of(result, summary)
    content = re.sub(r'FilterResult\.of\(\s*[^,]+,\s*', 'FilterResult.of(result, ', content)
    
    # GitPushFilter:[29,77] bad type in conditional expression
    # return FilterResult.of(result, pushed.isEmpty() ? result.readStdout() : pushed);
    # Actually let's just make sure we didn't break things like `FilterResult.of(result, ...)` by replacing it again.
    
    with open(filepath, 'w', encoding='utf-8') as f:
        f.write(content)

for root, dirs, files in os.walk(r'c:\Users\katwa\OneDrive\Desktop\code-condenser\zap\src\main\java\com\zapproxy\filter'):
    for f in files:
        if f.endswith('.java'):
            process_file(os.path.join(root, f))
