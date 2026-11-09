# One server for many libraries, on AWS: a VM with Docker, one data disk per library, the router on 80 and 443.
# docs/CLOUD.md says how it fits together; scripts/fleet.sh runs the libraries on the server once it is up.
#
#   terraform init && terraform apply -var 'libraries=["alpha","beta"]' -var domain=example.com -var key_name=my-key
#
# This is one VM. A second VM is a second copy of this with its own libraries; the router's route table on the
# first names where each library is. Snapshots of the data disks are the backups (aws_dlm or a nightly job).
terraform {
  required_version = ">= 1.5"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 5.0"
    }
  }
}

provider "aws" {
  region = var.region
}

data "aws_ami" "ubuntu" {
  most_recent = true
  owners      = ["099720109477"] # Canonical
  filter {
    name   = "name"
    values = ["ubuntu/images/hvm-ssd-gp3/ubuntu-noble-24.04-amd64-server-*"]
  }
}

resource "aws_security_group" "node" {
  name        = "${var.name}-node"
  description = "ResearchZosho node: the router's ports, and ssh from the operator"
  ingress {
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }
  ingress {
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }
  ingress {
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = var.ssh_from
  }
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

resource "aws_instance" "node" {
  ami                    = data.aws_ami.ubuntu.id
  instance_type          = var.instance_type
  key_name               = var.key_name
  vpc_security_group_ids = [aws_security_group.node.id]
  root_block_device {
    volume_size = 30
    volume_type = "gp3"
  }
  user_data = <<-CLOUDINIT
    #cloud-config
    package_update: true
    packages: [docker.io, docker-compose-v2, git]
    runcmd:
      - systemctl enable --now docker
      - usermod -aG docker ubuntu
      - mkdir -p /srv/researchzosho && chown ubuntu:ubuntu /srv/researchzosho
  CLOUDINIT
  tags = { Name = "${var.name}-node" }
}

# one disk per library: the library and its settings live on it, and a snapshot of it is the backup
resource "aws_ebs_volume" "library" {
  for_each          = toset(var.libraries)
  availability_zone = aws_instance.node.availability_zone
  size              = var.disk_gb
  type              = "gp3"
  tags              = { Name = "${var.name}-${each.key}", Library = each.key }
}

resource "aws_volume_attachment" "library" {
  for_each    = aws_ebs_volume.library
  device_name = "/dev/sd${substr("fghijklmnop", index(var.libraries, each.key), 1)}"
  volume_id   = each.value.id
  instance_id = aws_instance.node.id
}

resource "aws_eip" "node" {
  instance = aws_instance.node.id
  domain   = "vpc"
}
