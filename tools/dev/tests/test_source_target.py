import importlib.util,os,subprocess,sys,tempfile,unittest
from pathlib import Path
from unittest.mock import patch

class SourceTargetTest(unittest.TestCase):
 def test_non_pixel_is_rejected_before_install_or_settings_changes(self):
  source=Path(__file__).resolve().parents[1]/'run-source-tests.py'
  spec=importlib.util.spec_from_file_location('source_gate',source);module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
  calls=[]
  def run(args,**kwargs):
   calls.append(args)
   return subprocess.CompletedProcess(args,0,'37\n' if args[-1]=='ro.build.version.sdk' else 'RMX1825\n','')
  with tempfile.TemporaryDirectory() as temp,patch.dict(os.environ,{'ANDROID_SERIAL':'neutral-target'}),patch.object(sys,'argv',['run-source-tests.py']),patch.object(module.subprocess,'run',side_effect=run):
   module.ROOT=Path(temp)
   with self.assertRaisesRegex(ValueError,'Pixel 6'):module.main()
  self.assertEqual([args[-1] for args in calls],['ro.build.version.sdk','ro.product.device'])
