import sys,unittest,urllib.request
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from probe_samples import PolicyRedirect

class RedirectTest(unittest.TestCase):
 def test_new_host_is_derived_by_transport_and_credentials_are_not_forwarded(self):
  request=urllib.request.Request('https://old.example.invalid/embed',headers={'Referer':'https://old.example.invalid/','Range':'bytes=0-8191','Authorization':'Bearer neutral','Cookie':'neutral=fixture'})
  request.add_unredirected_header('Host','old.example.invalid')
  result=PolicyRedirect({'old.example.invalid','new.example.invalid'}).redirect_request(request,None,301,'',{},'https://new.example.invalid/embed')
  self.assertFalse(result.has_header('Host'));self.assertFalse(result.has_header('Authorization'));self.assertFalse(result.has_header('Cookie'))
  self.assertEqual(result.get_header('Range'),'bytes=0-8191');self.assertEqual(result.host,'new.example.invalid')
 def test_unapproved_destinations_stay_rejected(self):
  for url in ('http://old.example.invalid/embed','https://old.example.invalid:444/embed','https://user:password@old.example.invalid/embed','https://other.example.invalid/embed'):
   with self.assertRaises(ValueError):PolicyRedirect({'old.example.invalid'}).redirect_request(urllib.request.Request('https://old.example.invalid/'),None,302,'',{},url)
