"""Real model regression on an isolated copy of an existing Vue application.
Creates and removes only a temporary account and its own database rows.
"""
from pathlib import Path
from urllib.parse import urlparse
import argparse
import hashlib
import json
import os
import re
import shutil
import uuid
import pymysql
import requests
from playwright.sync_api import sync_playwright, expect

ROOT = Path(__file__).resolve().parents[1]


def datasource():
    values = {}
    for name in ['application.yml', 'application-local.yml']:
        source = ROOT / 'src/main/resources' / name
        if not source.exists():
            continue
        stack = []
        for line in source.read_text('utf-8-sig').splitlines():
            match = re.match(r'^(\s*)([\w.-]+):(?:\s*(.*))?$', line)
            if not match:
                continue
            indent = len(match[1])
            while stack and stack[-1][0] >= indent:
                stack.pop()
            stack.append((indent, match[2]))
            key = '.'.join(part for _, part in stack)
            if key.startswith('spring.datasource.') and match[3]:
                values[key.rsplit('.', 1)[-1]] = match[3].strip().strip('\"\'')
    url = urlparse(values['url'].removeprefix('jdbc:'))
    return pymysql.connect(host=url.hostname, port=url.port or 3306, user=values['username'],
                           password=values['password'], database=url.path.lstrip('/'), charset='utf8mb4', autocommit=True)


def verify(source_app_id, base_url):
    output = ROOT / 'tmp/live-visual-editing'
    output.mkdir(parents=True, exist_ok=True)
    os.environ['TEMP'] = os.environ['TMP'] = str(ROOT / 'tmp/jvm')
    account, password = 'visual_' + uuid.uuid4().hex[:16], uuid.uuid4().hex
    connection = datasource()
    user_id = app_id = None
    session = requests.Session()
    source = ROOT / 'tmp/code_output' / f'vue_project_{source_app_id}'
    source_files = {str(p.relative_to(source)): hashlib.sha256(p.read_bytes()).hexdigest()
                    for p in source.rglob('*') if p.is_file() and 'node_modules' not in p.parts and 'dist' not in p.parts}
    try:
        response = session.post(base_url + '/api/user/register', json={
            'userAccount': account, 'userPassword': password, 'checkPassword': password}, timeout=20).json()
        assert response['code'] == 0, response
        user_id = int(response['data'])
        with connection.cursor() as cursor:
            cursor.execute('SELECT initPrompt, codeGenType FROM app WHERE id=%s AND isDelete=0', (source_app_id,))
            original = cursor.fetchone()
            assert original and original[1] == 'vue_project', 'Source app must be a Vue project'
            cursor.execute('INSERT INTO app (appName,initPrompt,codeGenType,userId) VALUES (%s,%s,%s,%s)',
                           ('可视化真实模型回归_' + account, original[0], 'vue_project', user_id))
            app_id = cursor.lastrowid
            cursor.executemany('INSERT INTO chat_history (message,messageType,appId,userId) VALUES (%s,%s,%s,%s)', [
                (original[0], 'user', app_id, user_id),
                ('项目已生成，后续请使用工具读取现有项目并局部修改。', 'ai', app_id, user_id)])
        project = ROOT / 'tmp/code_output' / f'vue_project_{app_id}'
        shutil.copytree(source, project, ignore=shutil.ignore_patterns('.git', '*.log'))
        # Preserve package dist directories too; they contain Vite/compiler runtime files.
        with sync_playwright() as playwright:
            browser = playwright.chromium.launch(headless=True, executable_path=r'C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe')
            context = browser.new_context(viewport={'width': 1600, 'height': 1050})
            login = context.request.post(base_url + '/api/user/login', data={'userAccount': account, 'userPassword': password}).json()
            assert login['code'] == 0, login
            page = context.new_page()
            errors = []
            page.on('pageerror', lambda error: errors.append(str(error)))
            page.goto(f'{base_url}/app/chat/{app_id}')
            frame = page.frame_locator('iframe.preview-iframe')
            title = frame.locator('section.hero h1')
            expect(title).to_contain_text('你好，我是林舟', timeout=20000)
            page.get_by_role('button', name='编辑模式', exact=True).click()
            title.click()
            expect(page.locator('.selected-element-alert')).to_contain_text('你好，我是林舟')
            page.locator('textarea').fill('将选中的 h1 全部文本替换为“你好，我是derder”（不含引号，不添加句号、空格或表情）。只修改这一处标题文本，保留其他文字、样式、布局和功能。')
            with page.expect_request(lambda r: '/app/chat/gen/code?' in r.url) as request:
                page.get_by_role('button', name='发送', exact=True).click()
            assert 'message=' in request.value.url
            expect(title).to_have_text('你好，我是derder', timeout=240000)
            expect(page.get_by_role('button', name='编辑模式', exact=True)).to_be_enabled(timeout=240000)
            page.screenshot(path=str(output / 'real-model-success.png'), full_page=True)
            assert not errors, errors
            history = context.request.get(base_url + f'/api/chatHistory/app/{app_id}?pageSize=10').json()
            assert history['code'] == 0
            messages = '\n'.join(item['message'] for item in history['data']['records'])
            assert '修改文件' in messages or '写入文件' in messages
            result = {'headless': True, 'real_model': True, 'source_app_id': str(source_app_id),
                      'test_app_id': str(app_id), 'title': title.inner_text(), 'passed': True,
                      'browser_version': browser.version}
            (output / 'result.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), 'utf-8')
            context.request.post(base_url + '/api/user/logout')
            context.close()
            browser.close()
        for relative, checksum in source_files.items():
            assert hashlib.sha256((source / relative).read_bytes()).hexdigest() == checksum, f'Original source changed: {relative}'
        print(json.dumps(result, ensure_ascii=True))
    finally:
        # All cleanup predicates include the test account's exact identity.
        with connection.cursor() as cursor:
            if app_id and user_id:
                cursor.execute('DELETE FROM chat_history WHERE appId=%s AND userId=%s', (app_id, user_id))
                cursor.execute('DELETE FROM app WHERE id=%s AND userId=%s', (app_id, user_id))
            if user_id:
                cursor.execute('DELETE FROM user WHERE id=%s AND userAccount=%s', (user_id, account))
        connection.close()


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--source-app-id', type=int, required=True)
    parser.add_argument('--base-url', default='http://127.0.0.1:5173')
    args = parser.parse_args()
    verify(args.source_app_id, args.base_url.rstrip('/'))
