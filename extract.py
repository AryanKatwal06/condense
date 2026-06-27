import re
import os
import json

with open('zap_phase3_agent_prompt.md', 'r', encoding='utf-8') as f:
    text = f.read()

java_blocks = re.findall(r'```java\n(.*?)\n```', text, re.DOTALL)
print(f"Found {len(java_blocks)} java blocks")

files_created = 0

for block in java_blocks:
    pkg_match = re.search(r'^package\s+([a-zA-Z0-9_.]+);', block, re.MULTILINE)
    if not pkg_match:
        print("No package found, skipping")
        continue
    pkg = pkg_match.group(1)
    
    cls_match = re.search(r'(?:public\s+)?(?:final\s+)?(?:abstract\s+)?(?:class|interface|enum|record)\s+([a-zA-Z0-9_]+)', block)
    if not cls_match:
        print("No class found in block, skipping...")
        continue
    cls_name = cls_match.group(1)
    
    # Check if test file
    is_test = 'org.junit' in block or cls_name.endswith('Test') or cls_name.endswith('TestSupport')
    
    rel_dir = pkg.replace('.', '/')
    base_dir = os.path.join('zap', 'src', 'test', 'java') if is_test else os.path.join('zap', 'src', 'main', 'java')
    target_dir = os.path.join(base_dir, rel_dir)
    os.makedirs(target_dir, exist_ok=True)
    
    file_path = os.path.join(target_dir, f"{cls_name}.java")
    with open(file_path, 'w', encoding='utf-8') as out_f:
        out_f.write(block)
    files_created += 1
    # print(f"Created {file_path}")

print(f"Total java files created: {files_created}")

# reflect-config.json
json_blocks = re.findall(r'```json\n(.*?)\n```', text, re.DOTALL)
reflect_block = None
for block in json_blocks:
    if 'com.zapproxy' in block:
        reflect_block = block
        break

if reflect_block:
    # Wrap in array if not already
    if not reflect_block.strip().startswith('['):
        # The block might end with a comma, or might just be objects
        clean_block = reflect_block.strip()
        if clean_block.endswith(','):
            clean_block = clean_block[:-1]
        reflect_block = f"[{clean_block}]"
    
    try:
        new_entries = json.loads(reflect_block)
        print(f"Parsed {len(new_entries)} new entries for reflect-config.json")
        
        config_path = os.path.join('zap', 'src', 'main', 'resources', 'META-INF', 'native-image', 'reflect-config.json')
        if os.path.exists(config_path):
            with open(config_path, 'r', encoding='utf-8') as cf:
                try:
                    existing = json.load(cf)
                except json.JSONDecodeError:
                    existing = []
            
            # Append avoiding duplicates
            existing_names = {e.get('name') for e in existing}
            for entry in new_entries:
                if entry.get('name') not in existing_names:
                    existing.append(entry)
                    
            with open(config_path, 'w', encoding='utf-8') as cf:
                json.dump(existing, cf, indent=2)
            print("Updated reflect-config.json")
        else:
            print(f"reflect-config.json not found at {config_path}")
    except Exception as e:
        print(f"Error parsing or updating JSON: {e}")
