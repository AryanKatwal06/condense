import os
import re

def process_file(filepath):
    with open(filepath, 'r', encoding='utf-8') as f:
        content = f.read()

    # Identify if filter needs buffered string
    buffered_filters = [
        'GitStatusFilter', 'GitCommitFilter', 'GitLogFilter',
        'CargoTestFilter', 'CargoInstallFilter', 'CargoClippyFilter', 'GradleFilter'
    ]
    filename = os.path.basename(filepath)
    is_buffered = any(b in filename for b in buffered_filters)

    # 1. Replace FilterResult.of(raw, ...) with FilterResult.of(result, ...)
    content = re.sub(r'FilterResult\.of\(\s*result\.stdout\(\)\s*,', 'FilterResult.of(result,', content)
    content = re.sub(r'FilterResult\.of\(\s*raw\s*,', 'FilterResult.of(result,', content)
    
    # 2. Replace FilterResult.passthrough(result.stdout()) with FilterResult.passthrough(result)
    content = re.sub(r'FilterResult\.passthrough\(\s*result\.stdout\(\)\s*\)', 'FilterResult.passthrough(result)', content)
    content = re.sub(r'FilterResult\.passthrough\(\s*raw\s*\)', 'FilterResult.passthrough(result)', content)
    content = re.sub(r'FilterResult\.passthrough\(\s*result\.combined\(\)\s*\)', 'FilterResult.passthrough(result)', content)

    if is_buffered:
        content = content.replace('result.stdout()', 'result.readStdout()')
        content = content.replace('result.stderr()', 'result.readStderr()')
    else:
        # Instead of parsing raw, they should stream.
        # Most have: String raw = result.stdout().isBlank() ? result.stderr() : result.stdout();
        # Replace `raw.lines()` with `result.stdoutLines()`
        # Actually, let's just make ExecutionResult provide a handy Stream<String> lines()
        # and we can use try-with-resources. But to automate it safely:
        pass
        
    with open(filepath, 'w', encoding='utf-8') as f:
        f.write(content)

for root, dirs, files in os.walk(r'c:\Users\katwa\OneDrive\Desktop\code-condenser\zap\src\main\java\com\zapproxy\filter'):
    for f in files:
        if f.endswith('Filter.java'):
            process_file(os.path.join(root, f))
