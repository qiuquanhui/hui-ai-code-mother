"""Headless regression for the production build against VisualEditingE2eServer.
Run after starting the test server (8123) and Vite preview (4173). Requires Python Playwright.
"""
from pathlib import Path
from urllib.parse import urlparse, parse_qs
import argparse
import hashlib
import json
import os
import socket
import subprocess
import time
import urllib.request
import xml.etree.ElementTree as ET
from playwright.sync_api import sync_playwright, expect

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'tmp' / 'visual-editing'
OUT.mkdir(parents=True, exist_ok=True)


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def run(base_url):
    results = []
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(headless=True, executable_path=str(next((p for p in [Path(playwright.chromium.executable_path), Path(r'C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe')] if p.is_file()), Path(playwright.chromium.executable_path))), args=['--disable-gpu'])
        context = browser.new_context(viewport={'width': 1600, 'height': 1050})
        errors = []
        page = context.new_page()
        page.on('pageerror', lambda error: errors.append(str(error)))
        for kind, app_id in [('html', 991001), ('multi_file', 991002), ('vue_project', 991003)]:
            project = ROOT / 'tmp' / 'code_output' / f'{kind}_{app_id}'
            source = project / ('src/App.vue' if kind == 'vue_project' else 'index.html')
            before = source.read_text('utf-8')
            preserved = {str(f): digest(f) for f in project.rglob('*') if f.is_file()
                         and 'node_modules' not in f.parts and 'dist' not in f.parts
                         and f != source and not f.name.endswith('.log') and f.name != 'package-lock.json'}
            page.goto(f'{base_url}/app/chat/{app_id}')
            frame = page.frame_locator('iframe.preview-iframe')
            expect(frame.locator('#title')).to_have_text('Original title')
            edit = page.get_by_role('button', name='编辑模式', exact=True)
            expect(edit).to_be_enabled()
            initial_url = page.locator('iframe').get_attribute('src')
            # Normal preview navigation works; edit clicks must suppress navigation.
            frame.locator('#route-link').click()
            page.wait_for_function("document.querySelector('iframe').contentWindow.location.hash === '#details'")
            edit.click()
            frame.locator('#title').hover()
            expect(frame.locator('#hui-visual-hover')).to_be_visible()
            frame.locator('#title').click()
            alert = page.locator('.selected-element-alert')
            expect(alert).to_contain_text('h1#title')
            expect(alert).to_contain_text('#details')
            expect(frame.locator('#hui-visual-selected')).to_be_visible()
            assert frame.locator('#title').get_attribute('class') in [None, '']
            # Exit cleans both the parent selection and iframe overlays.
            page.get_by_role('button', name='退出编辑', exact=True).click()
            expect(alert).to_have_count(0)
            expect(frame.locator('#hui-visual-selected')).to_be_hidden()
            edit.click()
            frame.locator('#title').click()
            # Reload while editing: the editor reattaches and discards the previous selection.
            with page.expect_event('framenavigated', predicate=lambda f: f.parent_frame is not None):
                page.locator('iframe').evaluate('(f) => f.contentWindow.location.reload()')
            expect(page.get_by_role('button', name='退出编辑', exact=True)).to_be_visible()
            expect(alert).to_have_count(0)
            expect(frame.locator('#hui-visual-selected')).to_be_attached()
            # Malformed/unrelated postMessage traffic is ignored.
            page.evaluate("""() => {
                window.postMessage(null, location.origin);
                window.postMessage({channel:'hui-visual-editor',type:'ELEMENT_SELECTED',data:{elementInfo:{tagName:'FAKE'}}},location.origin);
            }""")
            expect(alert).to_have_count(0)
            # SVG className is not a string; selecting it must still produce a valid selector.
            frame.locator('circle').click()
            expect(alert).to_contain_text('circle')
            selector = alert.locator('code').inner_text()
            assert frame.locator(selector).count() == 1
            alert.locator('.ant-alert-close-icon').click()
            expect(alert).to_have_count(0)
            expect(frame.locator('#hui-visual-selected')).to_be_hidden()
            # Suppress interactive preview links during editing.
            frame.locator('#route-link').click()
            expect(alert).to_contain_text('a#route-link')
            assert page.locator('iframe').evaluate('(f) => f.contentWindow.location.hash') == '#details'
            frame.locator('#title').click()
            expect(alert).to_contain_text('h1#title')
            page.locator('textarea').fill('把选中的标题改为 Edited title，保留其他内容')
            with page.expect_request(lambda request: '/app/chat/gen/code?' in request.url) as outgoing:
                page.get_by_role('button', name='发送', exact=True).click()
            sent = parse_qs(urlparse(outgoing.value.url).query)['message'][0]
            assert all(text in sent for text in ['h1#title', 'Original title', '#details', '选中元素信息'])
            expect(alert).to_have_count(0)
            expect(frame.locator('#title')).to_have_text('Edited title', timeout=90000)
            expect(edit).to_be_enabled(timeout=90000)
            assert page.locator('iframe').get_attribute('src') != initial_url
            assert source.read_text('utf-8').strip() == before.replace('Original title', 'Edited title').strip()
            for filename, checksum in preserved.items():
                assert digest(Path(filename)) == checksum, f'Unrelated file changed: {filename}'
            expect(frame.locator('#untouched')).to_have_text('Keep this content')
            if kind == 'vue_project':
                chat = page.locator('.messages-container')
                for tool in ['读取目录', '读取文件', '修改文件']:
                    expect(chat).to_contain_text(tool)
                assert 'Edited title' in (project / 'dist/index.html').read_text('utf-8') or any(
                    'Edited title' in f.read_text('utf-8') for f in (project / 'dist/assets').glob('*.js'))
            # Browser reload preserves edits and the saved element context.
            page.reload()
            expect(frame.locator('#title')).to_have_text('Edited title')
            expect(page.locator('.messages-container')).to_contain_text('h1#title')
            edit.click()
            frame.locator('#title').click()
            expect(alert).to_contain_text('Edited title')
            # A second edit must update real styles, including external CSS in multi-file mode.
            page.locator('textarea').fill('STYLE_EDIT 把选中标题的颜色改为 rgb(12, 34, 56)，保留其他内容')
            page.get_by_role('button', name='发送', exact=True).click()
            expect(frame.locator('#title')).to_have_css('color', 'rgb(12, 34, 56)', timeout=90000)
            expect(edit).to_be_enabled(timeout=90000)
            expect(frame.locator('#untouched')).to_have_text('Keep this content')
            if kind == 'multi_file':
                assert 'rgb(12, 34, 56)' in (project / 'style.css').read_text('utf-8')
                assert frame.locator('body').evaluate('() => window.fixtureReady') is True
                assert page.request.get(f'{base_url}/api/static/multi_file_{app_id}/style.css').headers.get('cache-control') == 'no-store'
            page.screenshot(path=str(OUT / f'{kind}.png'), full_page=True)
            assert not errors, errors
            results.append({'case': kind, 'passed': True, 'element_context': sent})

        # Non-owners have neither edit controls nor backend generation permission.
        viewer = browser.new_context(viewport={'width': 1600, 'height': 1050})
        viewer.add_cookies([{'name': 'e2eViewer', 'value': '1', 'url': base_url}])
        view = viewer.new_page()
        view.goto(f'{base_url}/app/chat/991003')
        expect(view.frame_locator('iframe').locator('#title')).to_have_text('Edited title')
        expect(view.get_by_role('button', name='编辑模式', exact=True)).to_have_count(0)
        expect(view.locator('textarea')).to_be_disabled()
        response = view.request.get(f'{base_url}/api/app/chat/gen/code?appId=991003&message=forbidden')
        assert 'event:generation_error' in response.text() and '"code":40101' in response.text()
        results.append({'case': 'non_owner_frontend_and_backend', 'passed': True})
        viewer.close()

        # A model/SSE failure must terminate generation without refreshing to a false success.
        page.goto(f'{base_url}/app/chat/991001')
        expect(page.frame_locator('iframe').locator('#title')).to_have_text('Edited title')
        old_url = page.locator('iframe').get_attribute('src')
        page.locator('textarea').fill('FAIL_STREAM')
        page.get_by_role('button', name='发送', exact=True).click()
        expect(page.locator('.messages-container')).to_contain_text('生成失败，请稍后重试', timeout=15000)
        expect(page.get_by_role('button', name='发送', exact=True)).to_be_enabled()
        assert page.locator('iframe').get_attribute('src') == old_url
        expect(page.frame_locator('iframe').locator('#title')).to_have_text('Edited title')
        results.append({'case': 'model_failure_does_not_report_success', 'passed': True})
        assert not errors, errors
        context.close()
        browser.close()
    (OUT / 'results.json').write_text(json.dumps({'headless': True, 'cases': results}, ensure_ascii=False, indent=2), 'utf-8')
    print(json.dumps({'headless': True, 'passed': len(results), 'report': str(OUT / 'results.json')}, ensure_ascii=True))


def start_services(java, base_url, backend_port=8123):
    """Start only test-owned processes and stop them on success or failure."""
    host = urlparse(base_url).hostname
    port = urlparse(base_url).port or 4173
    if host != '127.0.0.1':
        raise ValueError('--start-services requires a 127.0.0.1 base URL')
    for required_port in [backend_port, port]:
        with socket.socket() as probe:
            if probe.connect_ex(('127.0.0.1', required_port)) == 0:
                raise RuntimeError(f'Port {required_port} is already in use; stop the existing server first')
    report = ROOT / 'target/surefire-reports/TEST-com.hui.huiaicodemother.ai.tools.ProjectFileToolsTest.xml'
    tree = ET.parse(report)
    classpath = next(p.attrib['value'] for p in tree.findall('.//property') if p.attrib['name'] == 'java.class.path')
    args = ROOT / 'tmp/visual-e2e-java.args'
    args.write_text(f'-Dvisual.e2e.port={backend_port}\n-Dvisual.e2e=true\n-Dfile.encoding=UTF-8\n-Djava.io.tmpdir="'
                    + str(ROOT / 'tmp/jvm').replace('\\', '/') + '"\n-cp\n"'
                    + classpath.replace('\\', '/') + '"\ncom.hui.huiaicodemother.e2e.VisualEditingE2eServer\n', 'utf-8')
    env = dict(os.environ, npm_config_offline='true', npm_config_audit='false', npm_config_fund='false',
               npm_config_cache=str(ROOT / 'tmp/npm-cache'))
    flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
    processes = []
    try:
        preview = 'import {preview} from "vite"; await preview(' + json.dumps({'preview': {
            'host': host, 'port': port, 'strictPort': True,
            'proxy': {'/api': {'target': f'http://127.0.0.1:{backend_port}', 'changeOrigin': True}}
        }}) + ');'
        commands = [([java, '@' + str(args)], ROOT, ROOT / 'tmp/visual-e2e-server.log'),
                    (['node', '--input-type=module', '-e', preview], ROOT / 'hui-ai-code-mother-frontend', ROOT / 'tmp/visual-e2e-frontend.log')]
        for command, cwd, log_path in commands:
            with log_path.open('w', encoding='utf-8') as log:
                processes.append(subprocess.Popen(command, cwd=cwd, env=env, stdout=log, stderr=subprocess.STDOUT, creationflags=flags))
        deadline = time.monotonic() + 90
        while time.monotonic() < deadline:
            if any(p.poll() is not None for p in processes):
                raise RuntimeError('Test server exited; inspect tmp/visual-e2e-server.log and tmp/visual-e2e-frontend.log')
            try:
                with urllib.request.urlopen(base_url + '/api/e2e/health', timeout=2) as response:
                    if response.status == 200:
                        break
            except OSError:
                time.sleep(0.5)
        else:
            raise TimeoutError('Test server did not become ready in 90 seconds')
        run(base_url)
    finally:
        for process in reversed(processes):
            if process.poll() is None:
                # Terminate the exact child handle; taskkill /T needs WMI access on restricted Windows hosts.
                process.terminate()
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=10)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--base-url', default='http://127.0.0.1:4173')
    parser.add_argument('--backend-port', type=int, default=8123)
    parser.add_argument('--start-services', action='store_true', help='Start/stop the isolated Java fixture and Vite preview')
    parser.add_argument('--java', default=str(Path(os.environ.get('JAVA_HOME', '')) / 'bin/java.exe') if os.name == 'nt' else 'java')
    options = parser.parse_args()
    os.environ['TEMP'] = os.environ['TMP'] = str(ROOT / 'tmp/jvm')
    Path(os.environ['TEMP']).mkdir(parents=True, exist_ok=True)
    if options.start_services:
        start_services(options.java, options.base_url.rstrip('/'), options.backend_port)
    else:
        run(options.base_url.rstrip('/'))
