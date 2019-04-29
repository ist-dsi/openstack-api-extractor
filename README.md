The Openstack API documentation pages have a lot of syntax errors, to the point of Jsoup not being able to handle it, even
tough its parser is very lenient. To overcome that issue we asked Chromium to format the page source and then saved each page
in a file.

The saved files were obtained in these locations at 2019-03-08.

 - https://developer.openstack.org/api-ref/image/v2/
 - https://developer.openstack.org/api-ref/dns/
 - https://developer.openstack.org/api-ref/identity/v3/
 - https://developer.openstack.org/api-ref/block-storage/v3/index.html
 - https://developer.openstack.org/api-ref/compute/
 - https://developer.openstack.org/api-ref/network/v2/