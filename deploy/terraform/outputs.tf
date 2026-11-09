output "address" {
  description = "The node's public address: point *.<domain> here."
  value       = aws_eip.node.public_ip
}

output "libraries" {
  description = "Each library's disk, by name, to mount and give to its container's volume."
  value       = { for k, v in aws_ebs_volume.library : k => v.id }
}

output "next" {
  value = "ssh ubuntu@${aws_eip.node.public_ip}; mount each disk under /srv/researchzosho/<name>; then docker compose -f docker-compose.cloud.yml up -d and scripts/fleet.sh create <name> for each, with RZ_DOMAIN=${var.domain} RZ_HTTPS=1"
}
